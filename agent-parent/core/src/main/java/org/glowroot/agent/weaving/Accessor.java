/*
 * Copyright 2014-2016 the original author or authors.
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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

class Accessor {

    private final AccessorType accessorType;
    private final @Nullable Method method;
    private final @Nullable Field field;

    static Accessor fromMethod(Method method) {
        return new Accessor(method);
    }

    static Accessor fromField(Field field) {
        return new Accessor(field);
    }

    static Accessor arrayLength() {
        return new Accessor();
    }

    private Accessor(Method method) {
        accessorType = AccessorType.METHOD;
        this.method = method;
        this.field = null;
    }

    private Accessor(Field field) {
        accessorType = AccessorType.FIELD;
        this.method = null;
        this.field = field;
    }

    private Accessor() {
        accessorType = AccessorType.ARRAY_LENGTH;
        this.method = null;
        this.field = null;
    }

    Class<?> getValueType() {
        switch (accessorType) {
            case METHOD:
                checkNotNull(method);
                return method.getReturnType();
            case FIELD:
                checkNotNull(field);
                return field.getType();
            case ARRAY_LENGTH:
                return int.class;
            default:
                throw new AssertionError("Unexpected accessor type: " + accessorType);
        }
    }

    @Nullable
    Object evaluate(Object object) throws Exception {
        if (isArrayEvaluation(object)) {
            return evaluateArray((/*@Nullable*/ Object[]) object);
        }
        switch (accessorType) {
            case METHOD:
                checkNotNull(method);
                return method.invoke(object);
            case FIELD:
                checkNotNull(field);
                return field.get(object);
            case ARRAY_LENGTH:
                return Array.getLength(object);
            default:
                throw new AssertionError("Unexpected accessor type: " + accessorType);
        }
    }

    private boolean isArrayEvaluation(Object object) {
        return object instanceof Object[] && accessorType != AccessorType.ARRAY_LENGTH;
    }

    private Object evaluateArray(/*@Nullable*/ Object[] array) throws Exception {
        /*@Nullable*/ Object[] values = new Object[array.length];
        for (int i = 0; i < array.length; i++) {
            Object item = array[i];
            values[i] = item == null ? null : evaluate(item);
        }
        return values;
    }

    private enum AccessorType {
        METHOD, FIELD, ARRAY_LENGTH
    }
}
