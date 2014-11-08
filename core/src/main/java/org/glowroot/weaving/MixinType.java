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

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.MixinInit;

public class MixinType {

    private static final Logger logger = LoggerFactory.getLogger(MixinType.class);

    private final ImmutableList<String> targets;
    private final Type implementation;
    private final ImmutableList<Type> interfaces;
    @Nullable
    private final String initMethodName;
    private final byte[] implementationBytes;

    public static MixinType from(Mixin mixin, Class<?> implementation) throws IOException {
        ImmutableList<String> targets = ImmutableList.copyOf(mixin.target());
        List<Type> interfaces = Lists.newArrayList();
        for (Class<?> iface : implementation.getInterfaces()) {
            interfaces.add(Type.getType(iface));
        }
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
        ClassLoader loader = implementation.getClassLoader();
        String resourceName = implementation.getName().replace('.', '/') + ".class";
        URL url;
        if (loader == null) {
            url = ClassLoader.getSystemResource(resourceName);
        } else {
            url = loader.getResource(resourceName);
        }
        if (url == null) {
            throw new IllegalStateException("Could not find resource: " + resourceName);
        }
        byte[] implementationBytes = Resources.toByteArray(url);
        return new MixinType(targets, Type.getType(implementation), interfaces, initMethodName,
                implementationBytes);
    }

    private MixinType(List<String> targets, Type implementation, List<Type> interfaces,
            @Nullable String initMethodName, byte[] implementationBytes) {
        this.targets = ImmutableList.copyOf(targets);
        this.implementation = implementation;
        this.interfaces = ImmutableList.copyOf(interfaces);
        this.initMethodName = initMethodName;
        this.implementationBytes = implementationBytes;
    }

    ImmutableList<String> getTargets() {
        return targets;
    }

    Type getImplementation() {
        return implementation;
    }

    ImmutableList<Type> getInterfaces() {
        return interfaces;
    }

    @Nullable
    String getInitMethodName() {
        return initMethodName;
    }

    byte[] getImplementationBytes() {
        return implementationBytes;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("targets", targets)
                .add("implementation", implementation)
                .add("interfaces", interfaces)
                .add("initMethodName", initMethodName)
                .toString();
    }
}
