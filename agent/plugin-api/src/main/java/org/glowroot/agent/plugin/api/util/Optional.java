/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.plugin.api.util;

import org.glowroot.agent.plugin.api.checker.Nullable;

public class Optional<T> {

    private static final Optional<?> ABSENT = new Optional<Object>(null);

    private final @Nullable T value;

    public static <T> Optional<T> fromNullable(@Nullable T value) {
        if (value == null) {
            @SuppressWarnings("unchecked")
            Optional<T> t = (Optional<T>) ABSENT;
            return t;
        } else {
            return new Optional<T>(value);
        }
    }

    public static <T> Optional<T> of(T value) {
        if (value == null) {
            throw new NullPointerException();
        } else {
            return new Optional<T>(value);
        }
    }

    private Optional(@Nullable T value) {
        this.value = value;
    }

    public boolean isPresent() {
        return value != null;
    }

    public T get() {
        if (value == null) {
            throw new NullPointerException();
        } else {
            return value;
        }
    }

    public @Nullable T orNull() {
        return value;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Optional)) {
            return false;
        }
        Optional<?> other = (Optional<?>) obj;
        if (value == null) {
            return other.value == null;
        } else {
            return value.equals(other.value);
        }
    }

    @Override
    public int hashCode() {
        if (value == null) {
            // copied from com.google.common.base.Absent.hashCode()
            return 0x79a31aac;
        } else {
            // copied from com.google.common.base.Present.hashCode()
            return 0x598df91c + value.hashCode();
        }
    }
}
