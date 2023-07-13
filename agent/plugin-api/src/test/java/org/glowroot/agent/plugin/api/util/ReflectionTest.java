/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.plugin.api.util;

import java.lang.reflect.Method;

import com.google.common.base.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.plugin.api.internal.PluginService;
import org.glowroot.agent.plugin.api.internal.PluginServiceHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ReflectionTest {

    @BeforeAll
    public static void setUp() {
        PluginServiceHolder.set(mock(PluginService.class));
    }

    @Test
    public void shouldReturnNullMethodWhenClassIsNull() {
        assertThat(Reflection.getMethod(null, null)).isNull();
    }

    @Test
    public void shouldReturnNullMethodWhenMethodNotFound() {
        assertThat(Reflection.getMethod(String.class, "thereWillNeverBeMethodWithThisName"))
                .isNull();
    }

    @Test
    public void shouldReturnDefaultValueWhenMethodIsNull() {
        assertThat(Reflection.invokeWithDefault(null, null, "the default"))
                .isEqualTo("the default");
    }

    @Test
    public void shouldReturnDefaultValueWhenMethodReturnsNull() throws Exception {
        Method method = Optional.class.getMethod("orNull");
        assertThat(Reflection.invokeWithDefault(method, Optional.absent(), "the default"))
                .isEqualTo("the default");
    }

    @Test
    public void shouldReturnDefaultValueWhenMethodThrowsException() throws Exception {
        Method method = Optional.class.getMethod("get");
        assertThat(Reflection.invokeWithDefault(method, Optional.absent(), "the default"))
                .isEqualTo("the default");
    }
}
