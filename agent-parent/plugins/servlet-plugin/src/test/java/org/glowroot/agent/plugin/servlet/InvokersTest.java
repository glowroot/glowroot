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

import java.lang.reflect.Method;

import com.google.common.base.Optional;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

public class InvokersTest {

    private static ch.qos.logback.classic.Logger invokersLogger;
    private static ch.qos.logback.classic.Level priorLevel;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @BeforeClass
    public static void setUp() throws Exception {
        // this is to avoid expected errors from being logged during the test
        // which causes other tests to fail due to Container.checkAndReset()
        // TODO this would not be necessary if unit tests were separated from integration tests
        // (e.g. using maven-failsafe-plugin for integration tests)
        invokersLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Invokers.class);
        priorLevel = invokersLogger.getLevel();
        invokersLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        invokersLogger.setLevel(priorLevel);
    }

    @Test
    public void shouldReturnNullMethodWhenClassIsNull() {
        assertThat(Invokers.getMethod(null, null)).isNull();
    }

    @Test
    public void shouldReturnNullMethodWhenMethodNotFound() {
        assertThat(Invokers.getMethod(String.class, "thereWillNeverBeMethodWithThisName")).isNull();
    }

    @Test
    public void shouldReturnDefaultValueWhenMethodIsNull() {
        assertThat(Invokers.invoke(null, null, "the default")).isEqualTo("the default");
    }

    @Test
    public void shouldReturnDefaultValueWhenMethodReturnsNull() throws Exception {
        Method method = Optional.class.getMethod("orNull");
        assertThat(Invokers.invoke(method, Optional.absent(), "the default"))
                .isEqualTo("the default");
    }

    @Test
    public void shouldReturnDefaultValueWhenMethodThrowsException() throws Exception {
        Method method = Optional.class.getMethod("get");
        assertThat(Invokers.invoke(method, Optional.absent(), "the default"))
                .isEqualTo("the default");
    }
}
