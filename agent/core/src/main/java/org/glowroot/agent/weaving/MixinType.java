/*
 * Copyright 2013-2019 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.objectweb.asm.Type;

import org.glowroot.agent.weaving.PluginDetail.MixinClass;

@Value.Immutable
abstract class MixinType {

    static MixinType create(MixinClass mixinClass) {
        return ImmutableMixinType.builder()
                .addTargets(mixinClass.mixin().value())
                .addAllInterfaces(mixinClass.interfaces())
                .initMethodName(mixinClass.initMethodName())
                .implementationBytes(mixinClass.bytes())
                .build();
    }

    abstract ImmutableList<String> targets();
    abstract ImmutableList<Type> interfaces();
    abstract @Nullable String initMethodName();
    abstract byte[] implementationBytes();
}
