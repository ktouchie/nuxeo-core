/* 
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 * $Id$
 */

package org.nuxeo.ecm.core.api.security;

import java.io.Serializable;
import java.util.List;

/**
 * An ACL (Access Control List) is a list of ACEs (Access Control Entry).
 * <p>
 * An ACP may contain several ACL identified by a name.
 * This is to let external modules add security rules. There are 2 default
 * ACLs:
 * <ul>
 * <li> the <code>local</code> ACL - this is the default type of ACL that may
 * be defined by an user locally to a document (using a security UI).
 * <br>
 * This is the only ACL an user can change
 * <li> the <code>inherited</code> - this is a special ACL generated by merging
 * all document parents ACL. This ACL is read only (cannot be modified locally
 * on the document since it is inherited.
 * </ul>
 *
 * ACLs that are used by external modules cannot be modified by the user
 * through the security UI. These ACLs should be modified only programmaticaly
 * by the tool that added them.
 *
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
public interface ACL extends List<ACE>, Serializable, Cloneable {

    String LOCAL_ACL = "local";

    String INHERITED_ACL = "inherited";

    /**
     * Gets the ACL name.
     *
     * @return the ACL name
     */
    String getName();

    /**
     * Gets the ACEs defined by this list as an array.
     *
     * @return
     */
    ACE[] getACEs();

    /**
     * Sets the ACEs defined by this ACL.
     *
     * @param aces the ACE array
     */
    void setACEs(ACE[] aces);

    /**
     * Returns a recursive copy of the ACL sharing no mutable substructure with
     * the original.
     *
     * @return a copy
     */
    Object clone();

}
