/*
 * Copyright 2013-2015 the original author or authors.
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

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.PolyNull;

public class ClassNames {

    private ClassNames() {}

    public static @PolyNull String fromInternalName(@PolyNull String internalName) {
        if (internalName == null) {
            return null;
        } else {
            return internalName.replace('/', '.');
        }
    }

    public static String toInternalName(String className) {
        return className.replace('.', '/');
    }

    static ImmutableList<String> fromInternalNames(List<String> internalNames) {
        if (internalNames.isEmpty()) {
            // optimization for a common case
            return ImmutableList.of();
        }
        List<String> classNames = Lists.newArrayList();
        for (String internalName : internalNames) {
            classNames.add(internalName.replace('/', '.'));
        }
        return ImmutableList.copyOf(classNames);
    }
}
