/*
 * (C) Copyright 2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */

package org.nuxeo.ecm.core.storage.sql;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections.map.ReferenceMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.Fragment.State;

/**
 * This class holds persistence context information for one table.
 * <p>
 * All non-saved modified data is referenced here. At save time, the data is
 * sent to the database by the {@link Mapper}. The database will at some time
 * later be committed by the external transaction manager in effect.
 * <p>
 * Internally a fragment can be in at most one of the caches: pristine, created,
 * modified, deleted. The pristine cache survives a save(), and may be partially
 * invalidated after commit by other local or clustered contexts that committed
 * too.
 * <p>
 * Depending on the table, the context may hold {@link SimpleFragment}s, which
 * represent one row, {@link CollectionFragment}s, which represent several rows.
 * <p>
 * This class is not thread-safe, it should be tied to a single session and the
 * session itself should not be used concurrently.
 * 
 * @author Florent Guillaume
 */
public class Context {

    private static final Log log = LogFactory.getLog(Context.class);

    private final String tableName;

    /** Number of columns in table. */
    private final int tableSize;

    protected final Model model;

    // also accessed by Fragment.refetch()
    protected final Mapper mapper;

    protected final PersistenceContext persistenceContext;

    /**
     * The pristine fragments. All held data is identical to what is present in
     * the database and could be refetched if needed.
     * <p>
     * This contains fragment that are {@link State#PRISTINE} or
     * {@link State#ABSENT}, or in some cases {@link State#INVALIDATED_MODIFIED}
     * or {@link State#INVALIDATED_DELETED}.
     * <p>
     * This cache is memory-sensitive, a fragment can always be refetched if the
     * GC collects it.
     */
    protected final Map<Serializable, Fragment> pristine;

    /**
     * The fragments changed by the session.
     * <p>
     * This contains fragment that are {@link State#CREATED},
     * {@link State#MODIFIED} or {@link State#DELETED}.
     */
    protected final Map<Serializable, Fragment> modified;

    /**
     * The set of modified/created fragments that should be invalidated in other
     * sessions at post-commit time.
     */
    private final Set<Serializable> modifiedInTransaction;

    /**
     * The set of deleted fragments that should be invalidated in other sessions
     * at post-commit time.
     */
    private final Set<Serializable> deletedInTransaction;

    /**
     * The set of fragments that have to be invalidated as modified in this
     * session at post-commit time. Usage must be synchronized.
     */
    private final Set<Serializable> modifiedInvalidations;

    /**
     * The set of fragments that have to be invalidated as deleted in this
     * session at post-commit time. Usage must be synchronized.
     */
    private final Set<Serializable> deletedInvalidations;

    protected final boolean isCollection;

    @SuppressWarnings("unchecked")
    public Context(String tableName, Model model, Mapper mapper,
            PersistenceContext persistenceContext) {
        this.tableName = tableName;
        this.model = model;
        this.mapper = mapper;
        this.persistenceContext = persistenceContext;
        pristine = new ReferenceMap(ReferenceMap.HARD, ReferenceMap.SOFT);
        modified = new HashMap<Serializable, Fragment>();

        modifiedInTransaction = new HashSet<Serializable>();
        deletedInTransaction = new HashSet<Serializable>();
        modifiedInvalidations = new HashSet<Serializable>();
        deletedInvalidations = new HashSet<Serializable>();

        isCollection = model.isCollectionFragment(tableName);
        tableSize = mapper.getTableSize(tableName);
    }

    @Override
    public String toString() {
        return "Context(" + tableName + ')';
    }

    public String getTableName() {
        return tableName;
    }

    public int getTableSize() {
        return tableSize;
    }

    /**
     * Clears all the caches. Called by RepositoryManagement.
     */
    protected int clearCaches() {
        // TODO there should be a synchronization here
        // but this is a rare operation and we don't call
        // it if a transaction is in progress
        int n = pristine.size();
        pristine.clear();
        modified.clear(); // not empty when rolling back before save
        modifiedInTransaction.clear();
        deletedInTransaction.clear();
        return n;
    }

    /**
     * Closes the context. Keeps around the {@link #pristine} and
     * {@link #absent} caches (to avoid costly refills). These two caches are
     * nevertheless still invalidatable.
     */
    public void close() {
        detachAll();
    }

    private void detachAll() {
        for (Fragment fragment : modified.values()) {
            fragment.setDetached();
        }
        modified.clear();
    }

    /**
     * Creates a new row in the context.
     * 
     * @param id the id
     * @param map the fragments map, or {@code null}
     * @return the created row
     * @throws StorageException if the row is already in the context
     */
    // FIXME: do we want to throw StorageException or IllegalStateException ?
    public SimpleFragment create(Serializable id, Map<String, Serializable> map)
    throws StorageException {
        if (pristine.containsKey(id) || modified.containsKey(id)) {
            throw new IllegalStateException("Row already registered: " + id);
        }
        return new SimpleFragment(id, State.CREATED, this, map);
    }

    /**
     * Gets a fragment.
     * <p>
     * If it's not in the context, fetch it from the mapper. If it's not in the
     * database, returns {@code null} or an absent fragment.
     * 
     * @param id the fragment id
     * @param allowAbsent {@code true} to return an absent fragment as an object
     *            instead of {@code null}
     * @return the fragment, or {@code null} if none is found and {@value
     *         allowAbsent} was {@code false}
     * @throws StorageException
     */
    public Fragment get(Serializable id, boolean allowAbsent)
    throws StorageException {
        Fragment fragment = getIfPresent(id);
        if (fragment == null) {
            return getFromMapper(id, allowAbsent);
        }
        if (fragment.getState() == State.DELETED) {
            return null;
        }
        return fragment;
    }

    /**
     * Gets a list of fragments.
     * <p>
     * If it's not in the context, fetch it from the mapper. If it's not in the
     * database, returns {@code null} or an absent fragment.
     * 
     * @param id the fragment id
     * @param allowAbsent {@code true} to return an absent fragment as an object
     *            instead of {@code null}
     * @return the fragment, or {@code null} if none is found and {@value
     *         allowAbsent} was {@code false}
     * @throws StorageException
     */
    public List<Fragment> getMulti(List<Serializable> ids, boolean allowAbsent)
    throws StorageException {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        // find ids we don't have in cache
        List<Fragment> held = new ArrayList<Fragment>(ids.size());
        List<Serializable> fetchIds = new ArrayList<Serializable>(ids.size());
        for (Serializable id : ids) {
            Fragment fragment = getIfPresent(id);
            if (fragment == null) {
                fetchIds.add(id);
            } else {
                held.add(fragment); // held to avoid GC
            }
        }

        // fetch missing ones, and hold in list to avoid GC
        List<Fragment> fetched = getFromMapper(fetchIds, allowAbsent);

        // did we just fetch everything?
        if (fetched.size() == ids.size()) {
            return fetched;
        }

        // now they're all in cache
        List<Fragment> fragments = new ArrayList<Fragment>(ids.size());
        for (Serializable id : ids) {
            Fragment fragment = getIfPresent(id);
            if (fragment != null && fragment.getState() == State.DELETED) {
                fragment = null;
            }
            fragments.add(fragment);
        }

        // reference the lists up to here, for GC
        held.set(0, null);
        if (fetched.size() != 0) {
            fetched.set(0, null);
        }

        return fragments;
    }

    /**
     * Gets a fragment from the mapper.
     */
    protected Fragment getFromMapper(Serializable id, boolean allowAbsent)
    throws StorageException {
        if (persistenceContext.isIdNew(id)) {
            // the id has not been saved, so nothing exists yet in the database
            if (isCollection) {
                return mapper.makeEmptyCollectionRow(id, this);
            } else {
                return allowAbsent ? new SimpleFragment(id, State.ABSENT, this,
                        null) : null;
            }
        } else {
            if (isCollection) {
                return mapper.readCollectionRow(id, this);
            } else {
                Map<String, Serializable> map = mapper.readSingleRowMap(
                        tableName, id, this);
                if (map == null) {
                    return allowAbsent ? new SimpleFragment(id, State.ABSENT,
                            this, null) : null;
                }
                return new SimpleFragment(id, State.PRISTINE, this, map);
            }
        }
    }

    /**
     * Gets a list of fragments from the mapper.
     */
    protected List<Fragment> getFromMapper(List<Serializable> ids,
            boolean allowAbsent) throws StorageException {

        // find fragments we really want to fetch
        List<Serializable> fetchIds = new ArrayList<Serializable>(ids.size());
        for (Serializable id : ids) {
            if (!persistenceContext.isIdNew(id)) {
                fetchIds.add(id);
            }
        }

        // fetch these fragments in bulk
        List<Fragment> fetchFragments = new ArrayList<Fragment>(fetchIds.size());
        if (isCollection) {
            mapper.readCollectionsRows(fetchIds, this, fetchFragments);
        } else {
            Map<Serializable, Map<String, Serializable>> maps = mapper.readMultipleRowMaps(
                    tableName, fetchIds, this);
            for (Serializable id : fetchIds) {
                Map<String, Serializable> map = maps.get(id);
                Fragment fragment;
                if (map == null) {
                    fragment = allowAbsent ? new SimpleFragment(id,
                            State.ABSENT, this, null) : null;
                } else {
                    fragment = new SimpleFragment(id, State.PRISTINE, this, map);
                }
                fetchFragments.add(fragment);
            }
        }

        // that's all if we had no created fragment
        if (fetchIds.size() == ids.size()) {
            return fetchFragments;
        }

        // add empty (created) fragments
        List<Fragment> fragments = new ArrayList<Fragment>(ids.size());
        Iterator<Fragment> it = fetchFragments.iterator();
        for (Serializable id : ids) {
            Fragment fragment;
            if (persistenceContext.isIdNew(id)) {
                // the id has not been saved, so nothing exists yet in the
                // database
                if (isCollection) {
                    fragment = mapper.makeEmptyCollectionRow(id, this);
                } else {
                    fragment = allowAbsent ? new SimpleFragment(id,
                            State.ABSENT, this, null) : null;
                }
            } else {
                fragment = it.next();
            }
            fragments.add(fragment);
        }
        return fragments;
    }

    /**
     * Gets a fragment, if present.
     * <p>
     * If it's not in the context, returns {@code null}.
     * <p>
     * Called by {@link #get}, and by the {@link Mapper} to reuse known
     * hierarchy fragments in lists of children.
     */
    public Fragment getIfPresent(Serializable id) {
        Fragment fragment = pristine.get(id);
        if (fragment != null) {
            return fragment;
        }
        return modified.get(id);
    }

    /**
     * Removes a row from the context.
     * 
     * @param fragment
     * @throws StorageException
     */
    public void remove(Fragment fragment) throws StorageException {
        fragment.markDeleted();
    }

    /**
     * Removes a fragment from the database.
     * 
     * @param id the fragment id
     * @throws StorageException
     */
    protected void remove(Serializable id) throws StorageException {
        Fragment fragment = getIfPresent(id);
        if (fragment != null) {
            if (fragment.getState() != State.DELETED) {
                remove(fragment);
            }
        } else {
            // this registers it with the "modified" map
            new SimpleFragment(id, State.DELETED, this, null);
        }
    }

    /**
     * Allows for remapping a row upon save.
     * 
     * @param fragment the fragment
     * @param idMap the map of old to new ids
     */
    protected void remapFragmentOnSave(Fragment fragment,
            Map<Serializable, Serializable> idMap) throws StorageException {
        // subclasses change this
        // TODO XXX there are other references to id (versionableid,
        // targetid, etc).
    }

    /**
     * Finds the documents having dirty text or dirty binaries that have to be
     * reindexed as fulltext.
     * 
     * @param dirtyStrings set of ids, updated by this method
     * @param dirtyBinaries set of ids, updated by this method
     * @throws StorageException
     */
    public void findDirtyDocuments(Set<Serializable> dirtyStrings,
            Set<Serializable> dirtyBinaries) throws StorageException {
        for (Fragment fragment : modified.values()) {
            Serializable docId = null;
            switch (fragment.getState()) {
            case CREATED:
                docId = persistenceContext.getContainingDocument(fragment.getId());
                dirtyStrings.add(docId);
                dirtyBinaries.add(docId);
                break;
            case MODIFIED:
                Collection<String> dirty;
                if (isCollection) {
                    dirty = Collections.singleton(null);
                } else {
                    dirty = ((SimpleFragment) fragment).getDirty();
                }
                for (String key : dirty) {
                    PropertyType type = model.getFulltextFieldType(tableName,
                            key);
                    if (type == null) {
                        continue;
                    }
                    if (docId == null) {
                        docId = persistenceContext.getContainingDocument(fragment.getId());
                    }
                    if (type == PropertyType.STRING) {
                        dirtyStrings.add(docId);
                    } else if (type == PropertyType.BINARY) {
                        dirtyBinaries.add(docId);
                    }
                }
                break;
            default:
            }
        }
    }

    /**
     * Saves all the created, modified or deleted rows, except for the created
     * main rows which have already been done.
     * 
     * @param idMap the map of temporary ids to final ids to use in translating
     *            secondary created rows
     * @throws StorageException
     */
    public void save(Map<Serializable, Serializable> idMap)
    throws StorageException {
        for (Fragment fragment : modified.values()) {
            Serializable id = fragment.getId();
            switch (fragment.getState()) {
            case CREATED:
                /*
                 * Map temporary to persistent ids.
                 */
                Serializable newId = idMap.get(id);
                if (newId != null) {
                    fragment.setId(newId);
                    id = newId;
                }
                /*
                 * Do the creation.
                 */
                if (isCollection) {
                    mapper.insertCollectionRows((CollectionFragment) fragment);
                } else {
                    mapper.insertSingleRow((SimpleFragment) fragment);
                }
                fragment.setPristine();
                // modified map cleared at end of loop
                pristine.put(id, fragment);
                modifiedInTransaction.add(id);
                break;
            case MODIFIED:
                if (isCollection) {
                    mapper.updateCollectionRows((CollectionFragment) fragment);
                } else {
                    mapper.updateSingleRow((SimpleFragment) fragment);
                }
                fragment.setPristine();
                // modified map cleared at end of loop
                pristine.put(id, fragment);
                modifiedInTransaction.add(id);
                break;
            case DELETED:
                // TODO deleting non-hierarchy fragments is done by the database
                // itself as their foreign key to hierarchy is ON DELETE CASCADE
                mapper.deleteFragment(fragment);
                fragment.setDetached();
                // modified map cleared at end of loop
                deletedInTransaction.add(id);
                break;
            case PRISTINE:
                // cannot happen, but has been observed :(
                log.error("Found PRISTINE fragment in modified map: "
                        + fragment);
                break;
            default:
                throw new RuntimeException(fragment.toString());
            }
        }
        modified.clear();
    }

    /**
     * Called by the mapper when a fragment has been updated in the database.
     * 
     * @param id the id
     * @param wasModified {@code true} for a modification, {@code false} for a
     *            deletion
     */
    public void markInvalidated(Serializable id, boolean wasModified) {
        if (wasModified) {
            Fragment fragment = getIfPresent(id);
            if (fragment != null) {
                fragment.markInvalidatedModified();
            }
            modifiedInTransaction.add(id);
        } else { // deleted
            Fragment fragment = getIfPresent(id);
            if (fragment != null) {
                fragment.markInvalidatedDeleted();
            }
            deletedInTransaction.add(id);
        }
    }

    /**
     * Gathers invalidations from this context.
     */
    protected void gatherInvalidations(Invalidations invalidations) {
        invalidations.addModified(tableName, modifiedInTransaction);
        invalidations.addDeleted(tableName, deletedInTransaction);
        modifiedInTransaction.clear();
        deletedInTransaction.clear();
    }

    /**
     * Processes all invalidations accumulated. Called pre-transaction.
     */
    protected void processReceivedInvalidations() {
        synchronized (modifiedInvalidations) {
            for (Serializable id : modifiedInvalidations) {
                Fragment fragment = pristine.remove(id);
                if (fragment != null) {
                    fragment.setInvalidatedModified();
                }
            }
            modifiedInvalidations.clear();
        }
        synchronized (deletedInvalidations) {
            for (Serializable id : deletedInvalidations) {
                Fragment fragment = pristine.remove(id);
                if (fragment != null) {
                    fragment.setInvalidatedDeleted();
                }
            }
            deletedInvalidations.clear();
        }
    }

    /**
     * Checks all invalidations accumulated. Called post-transaction.
     */
    protected void checkReceivedInvalidations() {
        synchronized (modifiedInvalidations) {
            for (Serializable id : modifiedInvalidations) {
                if (modifiedInTransaction.contains(id)) {
                    throw new ConcurrentModificationException(
                    "Updating a concurrently modified value");
                }
            }
        }
        synchronized (deletedInvalidations) {
            for (Serializable id : deletedInvalidations) {
                if (modifiedInTransaction.contains(id)) {
                    throw new ConcurrentModificationException(
                    "Updating a concurrently modified value");
                }
            }
        }
    }

    /**
     * Processes invalidations received by another session or cluster node.
     * <p>
     * Invalidations from other local session can happen asynchronously at any
     * time (when the other session commits). Invalidations from another cluster
     * node happen when the transaction starts.
     */
    protected void invalidate(Invalidations invalidations) {
        Set<Serializable> set = invalidations.modified.get(tableName);
        if (set != null) {
            synchronized (modifiedInvalidations) {
                modifiedInvalidations.addAll(set);
            }
        }
        set = invalidations.deleted.get(tableName);
        if (set != null) {
            synchronized (deletedInvalidations) {
                deletedInvalidations.addAll(set);
            }
        }
    }

}
