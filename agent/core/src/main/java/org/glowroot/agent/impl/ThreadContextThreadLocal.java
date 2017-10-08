/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.impl;

import javax.annotation.Nullable;

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
// NOTE this is same as org.glowroot.agent.plugin.api.util.FastThreadLocal, but not genericized in
// order to help with stack frame maps
public class ThreadContextThreadLocal {

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Holder> threadLocal = new ThreadLocal<Holder>() {
        @Override
        protected Holder initialValue() {
            return new Holder(ThreadContextThreadLocal.this.initialValue());
        }
    };

    public @Nullable ThreadContextImpl get() {
        return threadLocal.get().value;
    }

    public void set(@Nullable ThreadContextImpl value) {
        threadLocal.get().value = value;
    }

    public Holder getHolder() {
        return threadLocal.get();
    }

    protected @Nullable ThreadContextImpl initialValue() {
        return null;
    }

    public static class Holder {

        private @Nullable ThreadContextImpl value;

        private Holder(@Nullable ThreadContextImpl value) {
            this.value = value;
        }

        public @Nullable ThreadContextImpl get() {
            return value;
        }

        public void set(@Nullable ThreadContextImpl value) {
            this.value = value;
        }
    }
}
