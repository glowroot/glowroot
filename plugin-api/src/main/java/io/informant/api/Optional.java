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
package io.informant.api;

import java.util.concurrent.ConcurrentMap;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;

/**
 * This class is modeled after Guava's Optional class. It can be useful for plugins when returning a
 * {@link ConcurrentMap} from {@link Message#getDetail()}. {@link ConcurrentMap} does not accept
 * {@code null} values, but if values are wrapped in {@code Optional}, then Informant will unwrap
 * the values and treat {@link Optional#absent()} the same as {@code null}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class Optional<T> {

    /**
     * Returns the contained instance, which must be present. If the instance might be absent, use
     * {@link #or(Object)} instead.
     * 
     * @return the contained instance
     * @throws IllegalStateException
     *             if the instance is absent ({@link #isPresent} returns {@code false})
     */
    public abstract T get();

    /**
     * Returns {@code true} if this holder contains a (non-{@code null}) instance.
     * 
     * @return {@code true} if this holder contains a (non-{@code null}) instance
     */
    public abstract boolean isPresent();

    /**
     * Returns the contained instance if it is present; {@code defaultValue} otherwise. If no
     * default value should be required because the instance is known to be present, use
     * {@link #get()} instead.
     * 
     * @param defaultValue
     * @return
     */
    @Nullable
    public abstract T or(@Nullable T defaultValue);

    /**
     * Returns the contained instance if it is present; {@code null} otherwise. If the instance is
     * known to be present, use {@link #get()} instead.
     * 
     * @return
     */
    @Nullable
    public abstract T orNull();

    /**
     * Returns an {@code Optional} instance with no contained reference.
     * 
     * @return an {@code Optional} instance with no contained reference
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> absent() {
        return (Optional<T>) Absent.INSTANCE;
    }

    /**
     * Returns an {@code Optional} instance with no contained reference.
     * 
     * This version can be helpful to avoid unchecked casting.
     * 
     * @param type
     * @return an {@code Optional} instance with no contained reference
     */
    @SuppressWarnings({"unchecked"})
    public static <T> Optional<T> absent(Class<T> type) {
        return (Optional<T>) Absent.INSTANCE;
    }

    /**
     * Returns an {@code Optional} instance containing the given non-{@code null} reference.
     * 
     * @param reference
     * @return an {@code Optional} instance containing the given non-{@code null} reference
     */
    public static <T> Optional<T> of(T reference) {
        if (reference == null) {
            throw new NullPointerException();
        }
        return new Present<T>(reference);
    }

    /**
     * If {@code nullableReference} is non-{@code null}, returns an {@code Optional} instance
     * containing that reference; otherwise returns {@link Optional#absent}.
     * 
     * @param nullableReference
     * @return an {@code Optional} instance containing the reference, or {@link Optional#absent} if
     *         {@code nullableReference} is {@code null}
     */
    public static <T extends /*@Nullable*/Object> Optional</*@NonNull*/T> fromNullable(
            T nullableReference) {
        if (nullableReference == null) {
            return absent();
        } else {
            return new Present</*@NonNull*/T>(nullableReference);
        }
    }

    private static class Absent extends Optional<Object> {
        private static final Absent INSTANCE = new Absent();
        @Override
        public Object get() {
            throw new IllegalStateException("Value is absent");
        }
        @Override
        public boolean isPresent() {
            return false;
        }
        @Override
        @Nullable
        public Object or(@Nullable Object defaultValue) {
            return defaultValue;
        }
        @Override
        @Nullable
        public Object orNull() {
            return null;
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
        public boolean equals(@Nullable Object o) {
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
        public T or(@Nullable T defaultValue) {
            return reference;
        }
        @Override
        @Nullable
        public T orNull() {
            return reference;
        }
    }
}
