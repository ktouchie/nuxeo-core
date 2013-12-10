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
 */

package org.nuxeo.ecm.core.convert.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Before;
import org.junit.Test;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.blobholder.SimpleBlobHolder;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.convert.api.ConversionService;
import org.nuxeo.ecm.core.convert.cache.ConversionCacheGCManager;
import org.nuxeo.ecm.core.convert.cache.ConversionCacheHolder;
import org.nuxeo.ecm.core.convert.cache.GCTask;
import org.nuxeo.ecm.core.convert.extension.Converter;
import org.nuxeo.ecm.core.convert.service.ConversionServiceImpl;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.NXRuntimeTestCase;

public class TestCGCache extends NXRuntimeTestCase {

    ConversionService cs;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        deployBundle("org.nuxeo.ecm.core.api");
        deployBundle("org.nuxeo.ecm.core.convert.api");
        deployBundle("org.nuxeo.ecm.core.convert");
        deployContrib("org.nuxeo.ecm.core.convert.tests", "OSGI-INF/convert-service-config-enabled-gc.xml");
        cs = Framework.getLocalService(ConversionService.class);

        //  fire the frameworkStarted event and make sure that the GC thread is started
        fireFrameworkStarted();

        // do testing configuration
        // cachesize = -1 (actually 0)
        // GC interval negative => interpreted as seconds
        ConversionServiceImpl.setMaxCacheSizeInKB(-1);
        GCTask.setGCIntervalInMinutes(-1000);
    }

    @Test
    public void testCGTask() throws Exception {

        Converter cv = deployConverter();
        assertNotNull(cv);

        int cacheSize1 = ConversionCacheHolder.getNbCacheEntries();
        BlobHolder bh = getBlobHolder();
        BlobHolder result = cs.convert("identity", bh, null);
        assertNotNull(result);

        int cacheSize2 = ConversionCacheHolder.getNbCacheEntries();
        // check new cache entry was created
        assertEquals(1, cacheSize2 - cacheSize1);

        // wait for the GCThread to run
        int retryCount = 0;
        int noRuns = ConversionCacheGCManager.getGCRuns();
        while ( ConversionCacheGCManager.getGCRuns() == noRuns && retryCount++ < 5) {
            Thread.sleep(1100);
        }
        assertTrue(ConversionCacheGCManager.getGCRuns() > 0);

        int cacheSize3 = ConversionCacheHolder.getNbCacheEntries();
        assertEquals(0, cacheSize3);
    }

    private Converter deployConverter() throws Exception{
        deployContrib("org.nuxeo.ecm.core.convert.tests", "OSGI-INF/converters-test-contrib3.xml");
        return ConversionServiceImpl.getConverter("identity");
    }

    private static BlobHolder getBlobHolder(){
        File file = FileUtils.getResourceFileFromContext("test-data/hello.doc");
        assertNotNull(file);
        assertTrue(file.length() > 0);
        Blob blob = new FileBlob(file);
        blob.setFilename("hello.doc");
        blob.setMimeType("application/msword");
        return new SimpleBlobHolder(blob);
    }

}
