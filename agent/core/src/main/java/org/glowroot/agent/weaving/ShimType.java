/*
 * Copyright 2015-2017 the original author or authors.
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

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;
import org.objectweb.asm.Type;

import org.glowroot.agent.plugin.api.weaving.Shim;

@Value.Immutable
abstract class ShimType {

    static ShimType create(Shim shim, Class<?> iface) {
        ImmutableShimType.Builder builder = ImmutableShimType.builder();
        String value = shim.value();
        Pattern pattern = AdviceBuilder.buildPattern(value);
        if (pattern == null) {
            builder.target(value);
        } else {
            builder.target("");
            builder.targetPattern(pattern);
        }
        builder.target(value);
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
    abstract @Nullable Pattern targetPattern();
    abstract ImmutableList<Method> shimMethods();
}
