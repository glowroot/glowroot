/*
 * Copyright 2015-2016 the original author or authors.
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

/**
 * Wrapper that implements optimized {@link ThreadLocal} access pattern ideal for heavily used
 * ThreadLocals.
 * 
 * It is faster to use a mutable holder object and always perform ThreadLocal.get() and never use
 * ThreadLocal.set(), because the value is more likely to be found in the ThreadLocalMap direct hash
 * slot and avoid the slow path ThreadLocalMap.getEntryAfterMiss().
 * 
 * Important: this thread local will live in ThreadLocalMap forever, so use with care.
 */
public class FastThreadLocal</*@Nullable*/ T> {

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Holder<T>> threadLocal = new ThreadLocal<Holder<T>>() {
        @Override
        protected Holder<T> initialValue() {
            return new Holder<T>(FastThreadLocal.this.initialValue());
        }
    };

    public T get() {
        return threadLocal.get().value;
    }

    public void set(T value) {
        threadLocal.get().value = value;
    }

    public Holder<T> getHolder() {
        return threadLocal.get();
    }

    protected T initialValue() {
        return null;
    }

    public static class Holder<T> {

        private T value;

        private Holder(T value) {
            this.value = value;
        }

        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value;
        }
    }
}
