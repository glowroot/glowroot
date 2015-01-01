/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.advicegen;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import org.glowroot.common.Reflections.ReflectiveException;

class PathEvaluator {

    private static final Splitter splitter = Splitter.on('.').omitEmptyStrings();

    private final Accessor[] accessors;
    private final String /*@Nullable*/[] remainingPath;

    PathEvaluator(Class<?> baseClass, String path) {
        List<String> parts = Lists.newArrayList(splitter.split(path));
        List<Accessor> accessors = Lists.newArrayList();
        Class<?> currClass = baseClass;
        while (!parts.isEmpty()) {
            String currPart = parts.remove(0);
            Accessor accessor = Beans.findAccessor(currClass, currPart);
            while (accessor == null && currClass.getComponentType() != null) {
                currClass = currClass.getComponentType();
                accessor = Beans.findAccessor(currClass, currPart);
            }
            if (accessor == null) {
                parts.add(0, currPart);
                break;
            }
            accessors.add(accessor);
            currClass = accessor.getValueType();
        }
        this.accessors = accessors.toArray(new Accessor[accessors.size()]);
        if (parts.isEmpty()) {
            remainingPath = null;
        } else {
            remainingPath = parts.toArray(new String[parts.size()]);
        }
    }

    @Nullable
    Object evaluateOnBase(Object base) throws ReflectiveException {
        Object curr = base;
        for (Accessor accessor : accessors) {
            curr = accessor.evaluate(curr);
            if (curr == null) {
                return null;
            }
        }
        if (remainingPath != null) {
            // too bad, revert to slow Beans
            return Beans.value(curr, remainingPath);
        }
        return curr;
    }
}
