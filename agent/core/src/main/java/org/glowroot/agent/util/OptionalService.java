/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.util;

import javax.annotation.Nullable;

import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Availability;

public abstract class OptionalService<T> {

    static <T> OptionalService<T> available(T service) {
        return new PresentOptionalService<T>(service);
    }

    static <T> OptionalService<T> unavailable(String reason) {
        return new AbsentOptionalService<T>(reason);
    }

    public abstract Availability getAvailability();

    public abstract @Nullable T getService();

    private static class PresentOptionalService<T> extends OptionalService<T> {

        private final Availability availability;
        private final T service;

        public PresentOptionalService(T service) {
            this.availability = Availability.newBuilder().setAvailable(true).build();
            this.service = service;
        }

        @Override
        public Availability getAvailability() {
            return availability;
        }

        @Override
        public T getService() {
            return service;
        }
    }

    private static class AbsentOptionalService<T> extends OptionalService<T> {

        private final Availability availability;

        public AbsentOptionalService(String reason) {
            this.availability = Availability.newBuilder()
                    .setAvailable(false)
                    .setReason(reason)
                    .build();
        }

        @Override
        public Availability getAvailability() {
            return availability;
        }

        @Override
        public @Nullable T getService() {
            return null;
        }
    }
}
