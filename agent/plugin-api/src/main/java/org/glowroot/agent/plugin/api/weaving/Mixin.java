/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.agent.plugin.api.weaving;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotation to designate a class as a mixin class. The fields and methods from the mixin class
 * will be added to the target types. The target types can include interfaces, in which case the
 * annotated class will be mixed in to all classes which implement any of those interfaces.
 * <p>
 * If the mixin class implements any interfaces, those interfaces will be added to the target types.
 * This is the recommended way to access methods that are mixed in to the target types.
 * <p>
 * Constructors are not mixed in to the target types, but a single method in the mixin class can be
 * annotated with {@literal @}{@code MixinInit} and this will be called exactly once at some point
 * during the construction of each target type (to be precise, it is called at the end of each
 * target type constructor that doesn't cascade to another constructor in the same type).
 * <p>
 * It's important to note that inline field initializers in the mixin class will not get added to
 * the target types (since at the bytecode level they are be part of the mixin constructor), so
 * field initializers must be placed in the {@literal @}{@code MixinInit}.
 */
@Target(TYPE)
@Retention(RUNTIME)
public @interface Mixin {
    String[] value();
}
