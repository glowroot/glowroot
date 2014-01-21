/*
 * Copyright 2013-2014 the original author or authors.
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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.api.PluginServices;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JdbcDriverTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.createWithFileDb();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    // can't just check MockDriverState.isLoaded() since need to check value in external jvm
    // may as well use trace attribute data to pass the value back from the external jvm
    @Test
    public void shouldNotTriggerMockJdbcDriverToLoad() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedSpans.class);
        String mockDriverLoaded = container.getTraceService().getLastTrace().getAttributes()
                .get("mock driver loaded");
        assertThat(mockDriverLoaded).isEqualTo("false");
    }

    public static class ShouldGenerateTraceWithNestedSpans implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            PluginServices pluginServices =
                    PluginServices.get("glowroot-integration-tests");
            pluginServices.setTraceAttribute("mock driver loaded",
                    Boolean.toString(MockDriverState.isLoaded()));
        }
    }
}
