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
package org.glowroot.weaving;

import java.lang.reflect.Method;
import java.util.List;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.MixinInit;

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
        ImmutableList<Class<?>> interfaces = ImmutableList.copyOf(implementation.getInterfaces());
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
                    logger.warn("@MixinInit method must return void: {}", implementation.getName());
                    continue;
                }
                initMethodName = method.getName();
            }
        }
        return new MixinType(targets, implementation, interfaces, initMethodName);
    }

    private MixinType(List<String> targets, Class<?> implementation,
            List<Class<?>> interfaces, @Nullable String initMethodName) {
        this.targets = ImmutableList.copyOf(targets);
        this.implementation = implementation;
        this.interfaces = ImmutableList.copyOf(interfaces);
        List<String> interfaceNames = Lists.newArrayList();
        for (Class<?> iface : interfaces) {
            interfaceNames.add(iface.getName());
        }
        this.interfaceNames = ImmutableList.copyOf(interfaceNames);
        this.initMethodName = initMethodName;
    }

    ImmutableList<String> getTargets() {
        return targets;
    }

    Class<?> getImplementation() {
        return implementation;
    }

    ImmutableList<Class<?>> getInterfaces() {
        return interfaces;
    }

    ImmutableList<String> getInterfaceNames() {
        return interfaceNames;
    }

    @Nullable
    String getInitMethodName() {
        return initMethodName;
    }

    @Override
    @Pure
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
