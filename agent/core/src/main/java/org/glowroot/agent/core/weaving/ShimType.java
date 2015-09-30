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
package org.glowroot.agent.core.weaving;

import java.io.IOException;
import java.lang.reflect.Method;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;
import org.objectweb.asm.Type;

import org.glowroot.agent.plugin.api.weaving.Shim;

@Value.Immutable
public abstract class ShimType {

    public static ShimType from(Shim shim, Class<?> iface) throws IOException {
        ImmutableShimType.Builder builder = ImmutableShimType.builder();
        builder.target(shim.value());
        builder.iface(Type.getType(iface));
        for (Method method : iface.getMethods()) {
            if (method.isAnnotationPresent(Shim.class)) {
                builder.addShimMethods(method);
            }
        }
        return builder.build();
    }

    abstract Type iface();
    abstract String target();
    abstract ImmutableList<Method> shimMethods();
}
