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
package org.glowroot.common.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.immutables.value.Value;

@Value.Immutable
public abstract class AdvancedConfig {

    public static final int OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER = 10;
    public static final int TRANSACTION_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER = 2;

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public boolean weavingTimer() {
        return false;
    }

    @Value.Default
    public int immediatePartialStoreThresholdSeconds() {
        return 60;
    }

    // used to limit memory requirement
    @Value.Default
    public int maxAggregateTransactionsPerTransactionType() {
        return 500;
    }

    // used to limit memory requirement
    // applied to individual traces, transaction aggregates and overall aggregates
    @Value.Default
    public int maxAggregateQueriesPerQueryType() {
        return 500;
    }

    // used to limit memory requirement, also used to help limit trace capture size
    @Value.Default
    public int maxTraceEntriesPerTransaction() {
        return 2000;
    }

    // used to limit memory requirement, also used to help limit trace capture size
    @Value.Default
    public int maxStackTraceSamplesPerTransaction() {
        return 10000;
    }

    @Value.Default
    public int mbeanGaugeNotFoundDelaySeconds() {
        return 60;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }
}
