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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipInputStream;

import com.google.common.io.ByteStreams;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.TransactionConfig;
import org.glowroot.tests.DetailMapTest.ShouldGenerateTraceWithNestedEntries;
import org.glowroot.tests.ProfilingTest.ShouldGenerateTraceWithProfile;

import static java.util.concurrent.TimeUnit.SECONDS;

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
        Trace.Header header = container.getTraceService().getLastTrace();
        InputStream in = container.getTraceService().getTraceExport(header.id());
        // then should not bomb
        ZipInputStream zipIn = new ZipInputStream(in);
        zipIn.getNextEntry();
        ByteStreams.toByteArray(zipIn);
    }

    @Test
    public void shouldExportTraceWithProfile() throws Exception {
        // given
        TransactionConfig transactionConfig = container.getConfigService().getTransactionConfig();
        transactionConfig.setProfilingIntervalMillis(20);
        container.getConfigService().updateTransactionConfig(transactionConfig);
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
        // when
        Trace.Header header = container.getTraceService().getLastTrace();
        InputStream in = container.getTraceService().getTraceExport(header.id());
        // then should not bomb
        ZipInputStream zipIn = new ZipInputStream(in);
        zipIn.getNextEntry();
        ByteStreams.toByteArray(zipIn);
    }

    @Test
    public void shouldExportActiveTrace() throws Exception {
        // given
        TransactionConfig transactionConfig = container.getConfigService().getTransactionConfig();
        transactionConfig.setProfilingIntervalMillis(20);
        container.getConfigService().updateTransactionConfig(transactionConfig);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    container.executeAppUnderTest(ShouldWaitForInterrupt.class);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                return null;
            }
        });
        // when
        Trace.Header header = container.getTraceService().getActiveTrace(5, SECONDS);
        InputStream in = container.getTraceService().getTraceExport(header.id());
        // then should not bomb
        ZipInputStream zipIn = new ZipInputStream(in);
        zipIn.getNextEntry();
        ByteStreams.toByteArray(zipIn);
        // clean up
        container.interruptAppUnderTest();
    }

    public static class ShouldWaitForInterrupt implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
            }
        }
    }
}
