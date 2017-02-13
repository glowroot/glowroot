/*
 * Copyright 2016-2017 the original author or authors.
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

import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.agent.tests.app.StackOverflowTester;

public class StackOverflowOOMIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // need memory limited javaagent
        if (StandardSystemProperty.JAVA_VM_NAME.value().startsWith("IBM")) {
            // baseline memory seems just slightly higher for IBM JVM
            container = JavaagentContainer.createWithExtraJvmArgs(ImmutableList.of("-Xmx80m"));
        } else {
            container = JavaagentContainer.createWithExtraJvmArgs(ImmutableList.of("-Xmx64m"));
        }
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
    public void testCausedBy() throws Exception {
        // given
        // when
        container.execute(StackOverflowOOMApp.class);
        // then
        // don't run OOM
    }

    public static class StackOverflowOOMApp implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() {
            transactionMarker();
        }

        @Override
        public void transactionMarker() {
            try {
                new StackOverflowTester().test();
            } catch (Exception e) {
            }
        }
    }
}
