/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.container.trace;

import javax.annotation.Nullable;

import org.immutables.value.Value;

import org.glowroot.local.store.StringComparator;

@Value.Immutable
public abstract class TraceQuery {
    @Value.Default
    public long from() {
        return 0;
    }
    @Value.Default
    public long to() {
        return Long.MAX_VALUE;
    }
    // nanoseconds
    @Value.Default
    public long durationLow() {
        return 0;
    }
    // nanoseconds
    public abstract @Nullable Long durationHigh();
    public abstract @Nullable String transactionType();
    public abstract @Nullable StringComparator transactionNameComparator();
    public abstract @Nullable String transactionName();
    public abstract @Nullable StringComparator headlineComparator();
    public abstract @Nullable String headline();
    public abstract @Nullable StringComparator errorComparator();
    public abstract @Nullable String error();
    public abstract @Nullable StringComparator userComparator();
    public abstract @Nullable String user();
    public abstract @Nullable String customAttributeName();
    public abstract @Nullable StringComparator customAttributeValueComparator();
    public abstract @Nullable String customAttributeValue();
    @Value.Default
    public boolean errorOnly() {
        return false;
    }
    @Value.Default
    public int limit() {
        return Integer.MAX_VALUE;
    }
}
