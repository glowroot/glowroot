/*
 * Copyright 2014-2017 the original author or authors.
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
package org.glowroot.agent.tests.javaagent;

import java.util.logging.LogManager;

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

// this test is needed to ensure nothing initializes java.util.logging before jboss-modules is able
// to set the system property "java.util.logging.manager" (see org.jboss.modules.Main)
public class LogManagerIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // this test only passes when glowroot is shaded, since otherwise the unshaded guava library
        // uses and initializes java.util.logging before
        Assume.assumeTrue(isShaded());
        // this test cannot use shared javaagent container since it needs to be first thing
        // that runs in JVM in order to test that java.util.logging.LogManager is not initialized
        container = JavaagentContainer.createWithExtraJvmArgs(ImmutableList
                .of("-Djava.util.logging.manager=" + CustomLogManager.class.getName()));
    }

    @AfterClass
    public static void tearDown() throws Exception {
        // need null check in case assumption is false in setUp()
        if (container != null) {
            container.close();
        }
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldTest() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldUseCustomLogManager.class);
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo(CustomLogManager.class.getName());
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.agent.shaded.org.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static class ShouldUseCustomLogManager implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws InterruptedException {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws InterruptedException {
            // this is just to pass the log manager back to the calling test
            Glowroot.setTransactionUser(LogManager.getLogManager().getClass().getName());
        }
    }

    public static class CustomLogManager extends LogManager {}
}
