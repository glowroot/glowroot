/*
 * Copyright 2012-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.tests;

import java.io.InputStream;
import java.util.zip.ZipInputStream;

import com.google.common.io.ByteStreams;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.trace.Trace;
import org.glowroot.tests.DetailMapTest.ShouldGenerateTraceWithNestedEntries;
import org.glowroot.tests.ProfilingTest.ShouldGenerateTraceWithProfile;

public class ExportTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldExportTrace() throws Exception {
        // given
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedEntries.class);
        // when
        Trace trace = container.getTraceService().getLastTrace();
        InputStream in = container.getTraceService().getTraceExport(trace.getId());
        // then should not bomb
        ZipInputStream zipIn = new ZipInputStream(in);
        zipIn.getNextEntry();
        ByteStreams.toByteArray(zipIn);
    }

    @Test
    public void shouldExportTraceWithProfile() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setProfilingIntervalMillis(20);
        container.getConfigService().updateGeneralConfig(generalConfig);
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
        // when
        Trace trace = container.getTraceService().getLastTrace();
        InputStream in = container.getTraceService().getTraceExport(trace.getId());
        // then should not bomb
        ZipInputStream zipIn = new ZipInputStream(in);
        zipIn.getNextEntry();
        ByteStreams.toByteArray(zipIn);
    }
}
