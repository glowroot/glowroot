/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.dynamicadvice;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PathEvaluator {

    private static final Splitter splitter = Splitter.on('.').omitEmptyStrings();

    private final MethodOrField[] accessors;
    private final String/*@Nullable*/[] remainingPath;

    public PathEvaluator(Class<?> baseClass, String path) {
        List<String> parts = Lists.newArrayList(splitter.split(path));
        List<MethodOrField> accessors = Lists.newArrayList();
        Class<?> currClass = baseClass;
        while (!parts.isEmpty()) {
            String currPart = parts.remove(0);
            AccessibleObject accessor = Beans.findAccessor(currClass, currPart);
            if (accessor == null) {
                parts.add(0, currPart);
                break;
            }
            accessor.setAccessible(true);
            if (accessor instanceof Method) {
                accessors.add(new MethodOrField((Method) accessor, null));
                currClass = ((Method) accessor).getReturnType();
            } else {
                accessors.add(new MethodOrField(null, (Field) accessor));
                currClass = ((Field) accessor).getType();
            }
        }
        this.accessors = accessors.toArray(new MethodOrField[accessors.size()]);
        if (parts.isEmpty()) {
            remainingPath = null;
        } else {
            remainingPath = parts.toArray(new String[parts.size()]);
        }
    }

    @Nullable
    public Object evaluateOnBase(Object base) throws IllegalArgumentException,
            IllegalAccessException, InvocationTargetException {
        Object curr = base;
        for (MethodOrField accessor : accessors) {
            Method method = accessor.method;
            if (method != null) {
                curr = method.invoke(curr);
            } else {
                Field field = accessor.field;
                if (field == null) {
                    throw new AssertionError("both method and field cannot be null");
                }
                curr = field.get(curr);
            }
        }
        if (remainingPath != null) {
            // too bad, revert to slow Beans
            return Beans.value(curr, remainingPath);
        }
        return curr;
    }

    private static class MethodOrField {

        @Nullable
        private final Method method;
        @Nullable
        private final Field field;

        private MethodOrField(@Nullable Method method, @Nullable Field field) {
            this.method = method;
            this.field = field;
        }
    }
}
