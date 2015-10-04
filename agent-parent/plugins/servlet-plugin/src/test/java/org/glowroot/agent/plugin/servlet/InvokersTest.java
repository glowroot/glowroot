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
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import org.glowroot.agent.it.harness.impl.SpyingLogbackFilter;
import org.glowroot.agent.plugin.servlet.Invokers;

import static org.assertj.core.api.Assertions.assertThat;

public class InvokersTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @After
    public void afterEachTest() {
        if (SpyingLogbackFilter.active()) {
            SpyingLogbackFilter.clearMessages();
        }
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
