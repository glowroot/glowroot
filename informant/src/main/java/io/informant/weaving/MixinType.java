/*
 * Copyright 2013 the original author or authors.
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
package io.informant.weaving;

import java.lang.reflect.Method;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.weaving.Mixin;
import io.informant.api.weaving.MixinInit;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MixinType {

    private static final Logger logger = LoggerFactory.getLogger(MixinType.class);

    private final ImmutableList<String> targets;
    private final Class<?> implementation;
    private final ImmutableList<Class<?>> interfaces;
    private final ImmutableList<String> interfaceNames;
    @Nullable
    private final String initMethodName;

    public static MixinType from(Mixin mixin, Class<?> implementation) {
        ImmutableList<String> targets = ImmutableList.copyOf(mixin.target());
        // Class.getInterfaces() returns Class[] in jdk 5 so must be casted to Class<?>[]
        // (this method was corrected in jdk 6 to return Class<?>[])
        ImmutableList<Class<?>> interfaces =
                ImmutableList.copyOf((Class<?>[]) implementation.getInterfaces());
        String initMethodName = null;
        for (Method method : implementation.getDeclaredMethods()) {
            if (method.getAnnotation(MixinInit.class) != null) {
                if (initMethodName != null) {
                    logger.error("mixin has more than one @MixinInit: {}",
                            implementation.getName());
                    continue;
                }
                if (method.getParameterTypes().length > 0) {
                    logger.error("@MixinInit method cannot have any parameters: {}",
                            implementation.getName());
                    continue;
                }
                if (method.getReturnType() != void.class) {
                    logger.warn("@MixinInit method must return void: {}",
                            implementation.getName());
                    continue;
                }
                initMethodName = method.getName();
            }
        }
        return new MixinType(targets, implementation, interfaces, initMethodName);
    }

    private MixinType(ImmutableList<String> targets, Class<?> implementation,
            ImmutableList<Class<?>> interfaces, @Nullable String initMethodName) {
        this.targets = targets;
        this.implementation = implementation;
        this.interfaces = interfaces;
        ImmutableList.Builder<String> interfaceNames = ImmutableList.builder();
        for (Class<?> type : interfaces) {
            interfaceNames.add(type.getName());
        }
        this.interfaceNames = interfaceNames.build();
        this.initMethodName = initMethodName;
    }

    public ImmutableList<String> getTargets() {
        return targets;
    }

    public Class<?> getImplementation() {
        return implementation;
    }

    public ImmutableList<Class<?>> getInterfaces() {
        return interfaces;
    }

    public ImmutableList<String> getInterfaceNames() {
        return interfaceNames;
    }

    @Nullable
    public String getInitMethodName() {
        return initMethodName;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("targets", targets)
                .add("implementation", implementation)
                .add("interfaces", interfaces)
                .add("interfaceNames", interfaceNames)
                .add("initMethodName", initMethodName)
                .toString();
    }
}
