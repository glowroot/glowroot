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
package org.glowroot.jvm;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.immutables.value.Value;

public abstract class OptionalService<T> {

    static <T> OptionalService<T> available(T service) {
        return new NonLazyOptionalService<T>(Availability.of(true, ""), service);
    }

    static <T> OptionalService<T> unavailable(String reason) {
        return new NonLazyOptionalService<T>(Availability.of(false, reason), null);
    }

    static <T> OptionalService<T> lazy(Supplier<OptionalService<T>> supplier) {
        return new LazyOptionalService<T>(supplier);
    }

    public abstract Availability getAvailability();

    public abstract @Nullable T getService();

    @Value.Immutable
    @JsonSerialize(as = Availability.class)
    public abstract static class AvailabilityBase {
        @Value.Parameter
        public abstract boolean isAvailable();
        // reason only needed when available is false
        @Value.Parameter
        public abstract String getReason();
    }

    private static class NonLazyOptionalService<T> extends OptionalService<T> {

        private final Availability availability;
        private final @Nullable T service;

        public NonLazyOptionalService(Availability availability, @Nullable T service) {
            this.availability = availability;
            this.service = service;
        }

        @Override
        public Availability getAvailability() {
            return availability;
        }

        @Override
        public @Nullable T getService() {
            return service;
        }
    }

    private static class LazyOptionalService<T> extends OptionalService<T> {

        private final Supplier<OptionalService<T>> supplier;

        LazyOptionalService(Supplier<OptionalService<T>> supplier) {
            this.supplier = Suppliers.memoize(supplier);
        }

        @Override
        public Availability getAvailability() {
            return supplier.get().getAvailability();
        }

        @Override
        public @Nullable T getService() {
            return supplier.get().getService();
        }
    }
}
