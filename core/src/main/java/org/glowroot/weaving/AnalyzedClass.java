/*
 * Copyright 2012-2014 the original author or authors.
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

import java.lang.reflect.Modifier;
import java.util.List;

import javax.annotation.Nullable;

import org.immutables.value.Value;

// an AnalyzedClass is never created for Object.class
@Value.Immutable
abstract class AnalyzedClass {

    abstract int modifiers();
    abstract String name();
    // null superName means the super class is Object.class
    // (an AnalyzedClass is never created for Object.class)
    abstract @Nullable String superName();
    abstract List<String> interfaceNames();
    abstract List<AnalyzedMethod> analyzedMethods();
    abstract List<MixinType> mixinTypes();

    // not using @Value.Derived to keep down memory footprint
    boolean isInterface() {
        return Modifier.isInterface(modifiers());
    }

    // not using @Value.Derived to keep down memory footprint
    boolean isAbstract() {
        return Modifier.isAbstract(modifiers());
    }

    boolean hasReweavableAdvice() {
        for (AnalyzedMethod analyzedMethod : analyzedMethods()) {
            for (Advice advice : analyzedMethod.advisors()) {
                if (advice.reweavable()) {
                    return true;
                }
            }
        }
        return false;
    }
}
