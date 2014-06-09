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
package org.glowroot.jvm;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.glowroot.markers.UsedByJsonBinding;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class OptionalService<T> {

    private final Availability availability;
    @Nullable
    private final T service;

    static <T> OptionalService<T> available(T service) {
        return new OptionalService<T>(new Availability(true, ""), service);
    }

    static <T> OptionalService<T> unavailable(String reason) {
        return new OptionalService<T>(new Availability(false, reason), null);
    }

    public OptionalService(Availability availability, @Nullable T service) {
        this.availability = availability;
        this.service = service;
    }

    public Availability getAvailability() {
        return availability;
    }

    @Nullable
    public T getService() {
        return service;
    }

    @UsedByJsonBinding
    @Immutable
    public static class Availability {

        private final boolean available;
        // reason only needed when available is false
        private final String reason;

        public Availability(boolean available, String reason) {
            this.available = available;
            this.reason = reason;
        }

        public boolean isAvailable() {
            return available;
        }

        public String getReason() {
            return reason;
        }
    }
}
