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
package org.glowroot.collector;

import javax.annotation.Nullable;

import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;
import org.immutables.value.Json;
import org.immutables.value.Value;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@Json.Marshaled
public abstract class TransactionSummary {

    public static final Ordering<TransactionSummary> orderingByTotalTimeDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(@Nullable TransactionSummary left,
                        @Nullable TransactionSummary right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    return Longs.compare(right.totalMicros(), left.totalMicros());
                }
            };

    public static final Ordering<TransactionSummary> orderingByAverageTimeDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(@Nullable TransactionSummary left,
                        @Nullable TransactionSummary right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    return Doubles.compare(right.totalMicros() / (double) right.transactionCount(),
                            left.totalMicros() / (double) left.transactionCount());
                }
            };

    public static final Ordering<TransactionSummary> orderingByTransactionCountDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(@Nullable TransactionSummary left,
                        @Nullable TransactionSummary right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    return Longs.compare(right.transactionCount(), left.transactionCount());
                }
            };

    public abstract @Nullable String transactionName();
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    public abstract long totalMicros();
    public abstract long transactionCount();
}
