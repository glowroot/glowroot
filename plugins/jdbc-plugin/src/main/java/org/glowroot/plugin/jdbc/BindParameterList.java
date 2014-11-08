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
package org.glowroot.plugin.jdbc;

import java.util.Arrays;
import java.util.Iterator;

import com.google.common.collect.Iterators;
import org.checkerframework.checker.nullness.qual.Nullable;

// micro-optimized list for bind parameters
class BindParameterList implements Iterable</*@Nullable*/Object> {

    @Nullable
    private Object[] parameters;
    private int size;

    static BindParameterList copyOf(BindParameterList bindParameterList) {
        return new BindParameterList(bindParameterList.parameters, bindParameterList.size);
    }

    BindParameterList(int capacity) {
        parameters = new Object[capacity];
    }

    private BindParameterList(@Nullable Object[] parameters, int size) {
        this.parameters = parameters.clone();
        this.size = size;
    }

    void set(int i, @Nullable Object parameter) {
        int capacity = parameters.length;
        if (i >= capacity) {
            // using same capacity increase formula as ArrayList
            capacity = capacity + (capacity >> 1);
            parameters = Arrays.copyOf(parameters, capacity);
        }
        parameters[i] = parameter;
        int newSize = i + 1;
        if (newSize > size) {
            size = newSize;
        }
    }

    int size() {
        return size;
    }

    void clear() {
        Arrays.fill(parameters, null);
        size = 0;
    }

    @Override
    public Iterator</*@Nullable*/Object> iterator() {
        return Iterators.limit(Iterators.forArray(parameters), size);
    }
}
