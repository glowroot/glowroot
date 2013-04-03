/**
 * Copyright 2012-2013 the original author or authors.
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

import java.util.List;

import checkers.igj.quals.ReadOnly;
import org.objectweb.asm.Type;

import io.informant.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class MixinMatcher {

    private MixinMatcher() {}

    static boolean isMatch(MixinType mixinType, Type targetType,
            @ReadOnly List<ParsedType> superTypes) {
        boolean targetTypeClassMatch = isTypeMatch(mixinType, targetType.getClassName());
        boolean superClassMatch = false;
        boolean alreadyImplementsMixin = false;
        for (ParsedType superType : superTypes) {
            if (isTypeMatch(mixinType, superType.getName())) {
                superClassMatch = true;
            }
            if (mixinType.getInterfaceNames().contains(superType.getName())) {
                alreadyImplementsMixin = true;
            }
            if (superClassMatch && alreadyImplementsMixin) {
                // nothing else to find out
                break;
            }
        }
        return (targetTypeClassMatch || superClassMatch) && !alreadyImplementsMixin;
    }

    private static boolean isTypeMatch(MixinType mixinType, String className) {
        // currently only exact matching is supported
        return mixinType.getTargets().contains(className);
    }
}
