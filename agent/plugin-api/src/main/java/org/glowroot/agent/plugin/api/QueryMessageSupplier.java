/*
 * Copyright 2016 the original author or authors.
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

/**
 * A (lazy) supplier of {@link QueryMessage} instances. Needs to be thread safe since transaction
 * thread creates it, but trace storage (and live viewing) is done in a separate thread.
 */
public abstract class QueryMessageSupplier {

    /**
     * Returns the {@code QueryMessage} for a {@link QueryEntry}.
     * 
     * The {@code QueryMessage} does not need to be thread safe if it is instantiated by the
     * implementation of this method.
     */
    public abstract QueryMessage get();

    protected QueryMessageSupplier() {}

    /**
     * Creates a {@code QueryMessageSupplier} for the specified {@code prefix} and {@code suffix}.
     */
    public static QueryMessageSupplier create(final String prefix, final String suffix) {
        return new QueryMessageSupplier() {
            @Override
            public QueryMessage get() {
                return QueryMessage.create(prefix, suffix);
            }
        };
    }

    /**
     * Creates a {@code QueryMessageSupplier} for the specified {@code prefix}.
     */
    public static QueryMessageSupplier create(final String prefix) {
        return new QueryMessageSupplier() {
            @Override
            public QueryMessage get() {
                return QueryMessage.create(prefix);
            }
        };
    }
}
