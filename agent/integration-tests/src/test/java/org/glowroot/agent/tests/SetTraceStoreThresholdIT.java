/*
 * Copyright 2015-2016 the original author or authors.
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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.tests.app.LevelOne;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class SetTraceStoreThresholdIT {

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
    public void shouldNotReadTrace() throws Exception {
        // when
        container.executeNoExpectedTrace(SetLargeTraceStoreThreshold.class);
        // then
        // should not collect trace, verified above by method executeNoExpectedTrace()
    }

    @Test
    public void shouldReadTrace() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(ProtoOptional.of(Integer.MAX_VALUE))
                        .build());
        // when
        container.execute(SetLargeAndThenSmallTraceStoreThreshold.class);
        // then
        // should collect trace, verified above by method execute()
    }

    @Test
    public void shouldReadTrace2() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(ProtoOptional.of(Integer.MAX_VALUE))
                        .build());
        // when
        container.execute(SetSmallAndThenLargeTraceStoreThreshold.class);
        // then
        // should collect trace, verified above by method execute()
    }

    public static class SetLargeTraceStoreThreshold implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            Glowroot.setTransactionSlowThreshold(Long.MAX_VALUE, MILLISECONDS);
            new LevelOne().call("a", "b");
        }
    }

    public static class SetLargeAndThenSmallTraceStoreThreshold
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            Glowroot.setTransactionSlowThreshold(Long.MAX_VALUE, MILLISECONDS);
            Glowroot.setTransactionSlowThreshold(0, MILLISECONDS);
            new LevelOne().call("a", "b");
        }
    }

    public static class SetSmallAndThenLargeTraceStoreThreshold
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            Glowroot.setTransactionSlowThreshold(0, MILLISECONDS);
            Glowroot.setTransactionSlowThreshold(Long.MAX_VALUE, MILLISECONDS);
            new LevelOne().call("a", "b");
        }
    }
}
