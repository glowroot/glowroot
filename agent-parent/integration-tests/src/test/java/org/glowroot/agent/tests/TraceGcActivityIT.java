/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.tests;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class TraceGcActivityIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
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
    public void shouldTestGarbageCollection() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldGenerateGarbage.class);
        // then
        long collectionCount = 0;
        long collectionTime = 0;
        for (Trace.GarbageCollectionActivity gcActivity : trace.getHeader().getGcActivityList()) {
            collectionCount += gcActivity.getCount();
            collectionTime += gcActivity.getTotalMillis();
        }
        assertThat(collectionCount).isGreaterThanOrEqualTo(5);
        assertThat(collectionTime).isGreaterThanOrEqualTo(5);
    }

    public static class ShouldGenerateGarbage implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            long collectionCountStart = collectionCount();
            long collectionTimeStart = collectionTime();
            while (collectionCount() - collectionCountStart < 5
                    || collectionTime() - collectionTimeStart < 5) {
                for (int i = 0; i < 1000; i++) {
                    createGarbage();
                }
            }
        }
        private static long collectionCount() {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            long total = 0;
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                total += gcBean.getCollectionCount();
            }
            return total;
        }
        private static long collectionTime() {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            long total = 0;
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                total += gcBean.getCollectionTime();
            }
            return total;
        }
        private Object createGarbage() {
            return new char[10000];
        }
    }
}
