/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.plugin.api.internal;

import org.glowroot.agent.plugin.api.ParameterHolder;
import org.glowroot.agent.plugin.api.checker.Nullable;

public class ParameterHolderImpl<T> implements ParameterHolder<T> {

    private @Nullable T value;

    public static <T> ParameterHolderImpl<T> create(@Nullable T value) {
        return new ParameterHolderImpl<T>(value);
    }

    private ParameterHolderImpl(@Nullable T value) {
        this.value = value;
    }

    @Override
    public @Nullable T get() {
        return value;
    }

    @Override
    public void set(@Nullable T value) {
        this.value = value;
    }
}
