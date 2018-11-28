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

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.immutables.value.Value;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

@Value.Immutable
interface PluginDetail {

    List<PointcutClass> pointcutClasses();
    List<MixinClass> mixinClasses();
    List<ShimClass> shimClasses();

    @Value.Immutable
    abstract class PointcutClass {

        abstract Type type();
        abstract Pointcut pointcut();
        abstract List<PointcutMethod> methods();
        abstract byte[] bytes();

        abstract boolean collocateInClassLoader();
        abstract @Nullable File pluginJar();
    }

    @Value.Immutable
    abstract class PointcutMethod {

        abstract String name();
        abstract String descriptor();
        abstract Set<Type> annotationTypes();
        abstract Map<Integer, List<Type>> parameterAnnotationTypes();

        @Value.Derived
        Method toAsmMethod() {
            return new Method(name(), descriptor());
        }
    }

    @Value.Immutable
    abstract class MixinClass {

        abstract Type type();
        abstract List<Type> interfaces();
        abstract Mixin mixin();
        abstract @Nullable String initMethodName();
        abstract byte[] bytes();
    }

    @Value.Immutable
    abstract class ShimClass {

        abstract Type type();
    }
}
