/*
 * Copyright 2013-2017 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.agent.tests.app.MockDriverState;

public class JdbcDriverIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // this test only passes when glowroot is shaded, since it is testing that
        // java.sql.DriverManager has been shaded to org.glowroot.agent.sql.DriverManager
        Assume.assumeTrue(isShaded());
        // running in embedded mode to make sure H2 library doesn't trigger other drivers to load
        // via java.sql.DriverManager
        container = new JavaagentContainer(null, true, ImmutableList.<String>of());
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
    public void shouldNotTriggerMockJdbcDriverToLoad() throws Exception {
        container.executeNoExpectedTrace(AssertMockDriverNotLoaded.class);
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.agent.shaded.org.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static class AssertMockDriverNotLoaded implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            if (MockDriverState.isLoaded()) {
                throw new Exception();
            }
        }
    }
}
