/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.plugin.servlet;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

public class ResponseInvokerTest {

    private static ch.qos.logback.classic.Logger responseInvokerLogger;
    private static ch.qos.logback.classic.Level priorLevel;

    @BeforeClass
    public static void setUp() throws Exception {
        // this is to avoid expected errors from being logged during the test
        // which causes other tests to fail due to Container.checkAndReset()
        // TODO this would not be necessary if unit tests were separated from integration tests
        // (e.g. using maven-failsafe-plugin for integration tests)
        responseInvokerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ResponseInvoker.class);
        priorLevel = responseInvokerLogger.getLevel();
        responseInvokerLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        responseInvokerLogger.setLevel(priorLevel);
    }

    @Test
    public void shouldNotFindServletResponseClass() {
        assertThat(ResponseInvoker.getServletResponseClass(Object.class)).isNull();
    }

    @Test
    public void shouldNotFindGetContentTypeMethod() {
        assertThat(new ResponseInvoker(Object.class).hasGetContentTypeMethod()).isFalse();
    }
}
