/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.api;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// modeled after guava's Optional class, didn't re-use guava's Optional because didn't want public
// api to depend on shaded guava
public abstract class Optional<T> {

    public abstract T get();

    public abstract boolean isPresent();

    public abstract T or(T defaultValue);

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> absent() {
        return (Optional<T>) Absent.INSTANCE;
    }

    // this version can be helpful to avoid unchecked casting
    @SuppressWarnings({ "unchecked", "unused" })
    public static <T> Optional<T> absent(Class<T> type) {
        return (Optional<T>) Absent.INSTANCE;
    }

    public static <T> Optional<T> of(T reference) {
        if (reference == null) {
            throw new NullPointerException();
        }
        return new Present<T>(reference);
    }

    public static <T> Optional<T> fromNullable(T nullableReference) {
        if (nullableReference == null) {
            return absent();
        } else {
            return new Present<T>(nullableReference);
        }
    }

    private static class Absent extends Optional<Object> {
        private static final Absent INSTANCE = new Absent();
        @Override
        public Object get() {
            throw new NullPointerException();
        }
        @Override
        public boolean isPresent() {
            return false;
        }
        @Override
        public Object or(Object defaultValue) {
            return defaultValue;
        }
    }

    private static class Present<T> extends Optional<T> {
        private final T reference;
        public Present(T reference) {
            this.reference = reference;
        }
        @Override
        public T get() {
            return reference;
        }
        @Override
        public boolean isPresent() {
            return true;
        }
        @Override
        public boolean equals(Object o) {
            if (o instanceof Present) {
                return ((Present<?>) o).get().equals(reference);
            } else {
                return false;
            }
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(reference);
        }
        @Override
        public T or(T defaultValue) {
            return reference;
        }
    }
}
