/*
 * Copyright 2014-2017 the original author or authors.
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
package org.glowroot.ui;

import org.glowroot.common.live.ImmutableOverallQuery;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallErrorSummaryCollector.OverallErrorSummary;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionErrorSummaryCollector.TransactionErrorSummary;
import org.glowroot.common.repo.AggregateRepository;

class ErrorCommonService {

    private final AggregateRepository aggregateRepository;
    private final LiveAggregateRepository liveAggregateRepository;

    ErrorCommonService(AggregateRepository aggregateRepository,
            LiveAggregateRepository liveAggregateRepository) {
        this.aggregateRepository = aggregateRepository;
        this.liveAggregateRepository = liveAggregateRepository;
    }

    // from is non-inclusive
    OverallErrorSummary readOverallErrorSummary(String agentRollupId, OverallQuery query,
            boolean autoRefresh) throws Exception {
        OverallErrorSummaryCollector collector = new OverallErrorSummaryCollector();
        long revisedFrom = query.from();
        long revisedTo;
        if (autoRefresh) {
            revisedTo = query.to();
        } else {
            revisedTo = liveAggregateRepository.mergeInOverallErrorSummary(agentRollupId, query,
                    collector);
        }
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeOverallErrorSummaryInto(agentRollupId, revisedQuery,
                    collector);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getOverallErrorSummary();
    }

    // query.from() is non-inclusive
    Result<TransactionErrorSummary> readTransactionErrorSummaries(String agentRollupId,
            OverallQuery query, ErrorSummarySortOrder sortOrder, int limit, boolean autoRefresh)
            throws Exception {
        TransactionErrorSummaryCollector collector = new TransactionErrorSummaryCollector();
        long revisedFrom = query.from();
        long revisedTo;
        if (autoRefresh) {
            revisedTo = query.to();
        } else {
            revisedTo = liveAggregateRepository.mergeInTransactionErrorSummaries(agentRollupId,
                    query, collector);
        }
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeTransactionErrorSummariesInto(agentRollupId, revisedQuery,
                    sortOrder, limit, collector);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getResult(sortOrder, limit);
    }
}
