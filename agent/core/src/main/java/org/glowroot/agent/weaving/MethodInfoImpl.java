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
package org.glowroot.agent.weaving;

import java.util.List;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.MethodInfo;

public class MethodInfoImpl implements MethodInfo {

    private final String name;
    private final Class<?> returnType;
    private final List<Class<?>> parameterTypes;
    private final String declaringClassName;
    private final @Nullable ClassLoader loader;

    public MethodInfoImpl(String name, Class<?> returnType, List<Class<?>> parameterTypes,
            String declaringClassName, @Nullable ClassLoader loader) {
        this.name = name;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.declaringClassName = declaringClassName;
        this.loader = loader;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Class<?> getReturnType() {
        return returnType;
    }

    @Override
    public List<Class<?>> getParameterTypes() {
        return parameterTypes;
    }

    @Override
    public String getDeclaringClassName() {
        return declaringClassName;
    }

    @Override
    public @Nullable ClassLoader getLoader() {
        return loader;
    }
}
