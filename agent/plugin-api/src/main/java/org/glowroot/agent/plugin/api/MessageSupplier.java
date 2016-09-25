/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.plugin.api;

import javax.annotation.Nullable;

/**
 * A (lazy) supplier of {@link Message} instances. Needs to be thread safe since transaction thread
 * creates it, but trace storage (and live viewing) is done in a separate thread.
 */
public abstract class MessageSupplier {

    /**
     * Returns the {@code Message} for a {@link TraceEntry}.
     * 
     * The {@code Message} does not need to be thread safe if it is instantiated by the
     * implementation of this method.
     */
    public abstract Message get();

    protected MessageSupplier() {}

    /**
     * Creates a {@code MessageSupplier} for the specified {@code message}.
     */
    public static MessageSupplier create(@Nullable final String message) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                return Message.create(message);
            }
        };
    }

    /**
     * Creates a {@code MessageSupplier} for the specified {@code template} and {@code args}.
     * 
     * The {@code template} can contain one or more placeholders <code>{}</code> that will be
     * substituted if/when the message text is needed by the specified {@code args}.
     */
    public static MessageSupplier create(final String template, final @Nullable String... args) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                return Message.create(template, args);
            }
        };
    }
}
