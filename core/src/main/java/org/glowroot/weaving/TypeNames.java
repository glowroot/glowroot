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

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class TypeNames {

    private TypeNames() {}

    /*@PolyNull*/
    public static String fromInternal(/*@PolyNull*/String typeName) {
        if (typeName == null) {
            return null;
        } else {
            return typeName.replace('/', '.');
        }
    }

    public static String toInternal(String typeName) {
        return typeName.replace('.', '/');
    }

    static ImmutableList<String> fromInternal(String/*@Nullable*/[] internalTypeNames) {
        if (internalTypeNames == null) {
            return ImmutableList.of();
        }
        List<String> typeNames = Lists.newArrayList();
        for (String typeName : internalTypeNames) {
            typeNames.add(typeName.replace('/', '.'));
        }
        return ImmutableList.copyOf(typeNames);
    }
}
