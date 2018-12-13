/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.central.repo;

import java.util.List;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.agent.api.Instrumentation.AlreadyInTransactionBehavior;
import org.glowroot.common2.repo.SyntheticResultRepository;

public interface SyntheticResultDao extends SyntheticResultRepository {

    // synthetic result records are not rolled up to their parent, but are stored directly for
    // rollups that have their own synthetic monitors defined
    void store(String agentRollupId, String syntheticMonitorId, String syntheticMonitorDisplay,
            long captureTime, long durationNanos, @Nullable String errorMessage) throws Exception;

    List<SyntheticResultRollup0> readLastFromRollup0(String agentRollupId,
            String syntheticMonitorId, int x) throws Exception;

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Rollup synthetic results",
            traceHeadline = "Rollup synthetic results: {{0}}", timer = "rollup synthetic results",
            alreadyInTransactionBehavior = AlreadyInTransactionBehavior.CAPTURE_NEW_TRANSACTION)
    void rollup(String agentRollupId) throws Exception;

    @Value.Immutable
    public interface SyntheticResultRollup0 {
        long captureTime();
        double totalDurationNanos();
        boolean error();
    }
}
