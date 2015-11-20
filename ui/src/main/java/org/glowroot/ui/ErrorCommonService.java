/*
 * Copyright 2014-2015 the original author or authors.
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

import java.util.List;

import org.glowroot.common.live.ImmutableOverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.LiveResult;
import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.AggregateRepository.ErrorSummarySortOrder;
import org.glowroot.storage.repo.AggregateRepository.OverallQuery;
import org.glowroot.storage.repo.ImmutableOverallQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TransactionErrorSummaryCollector;

class ErrorCommonService {

    private final AggregateRepository aggregateRepository;
    private final LiveAggregateRepository liveAggregateRepository;

    ErrorCommonService(AggregateRepository aggregateRepository,
            LiveAggregateRepository liveAggregateRepository) {
        this.aggregateRepository = aggregateRepository;
        this.liveAggregateRepository = liveAggregateRepository;
    }

    // from is non-inclusive
    OverallErrorSummary readOverallErrorSummary(OverallQuery query) throws Exception {
        LiveResult<OverallErrorSummary> liveResult = liveAggregateRepository
                .getLiveOverallErrorSummary(query.transactionType(), query.from(), query.to());
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? query.to() : liveResult.initialCaptureTime() - 1;
        long revisedFrom = query.from();
        long errorCount = 0;
        long transactionCount = 0;
        long lastCaptureTime = 0;
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            OverallErrorSummary overallSummary =
                    aggregateRepository.readOverallErrorSummary(revisedQuery);
            errorCount += overallSummary.errorCount();
            transactionCount += overallSummary.transactionCount();
            lastCaptureTime = overallSummary.lastCaptureTime();
            long lastRolledUpTime = overallSummary.lastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        if (liveResult != null) {
            for (OverallErrorSummary overallSummary : liveResult.get()) {
                errorCount += overallSummary.errorCount();
                transactionCount += overallSummary.transactionCount();
                // live results are ordered so no need for Math.max() here
                lastCaptureTime = overallSummary.lastCaptureTime();
            }
        }
        return ImmutableOverallErrorSummary.builder()
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .lastCaptureTime(lastCaptureTime)
                .build();
    }

    // query.from() is non-inclusive
    Result<TransactionErrorSummary> readTransactionErrorSummaries(OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit) throws Exception {
        LiveResult<List<TransactionErrorSummary>> liveResult =
                liveAggregateRepository.getLiveTransactionErrorSummaries(query.transactionType(),
                        query.from(), query.to());
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? query.to() : liveResult.initialCaptureTime() - 1;
        long revisedFrom = query.from();
        TransactionErrorSummaryCollector mergedTransactionErrorSummaries =
                new TransactionErrorSummaryCollector();
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeInTransactionErrorSummaries(mergedTransactionErrorSummaries,
                    revisedQuery, sortOrder, limit);
            long lastRolledUpTime = mergedTransactionErrorSummaries.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        if (liveResult != null) {
            for (List<TransactionErrorSummary> transactionSummaries : liveResult.get()) {
                // second arg (lastCaptureTime) doesn't matter any more (it was only needed above)
                mergedTransactionErrorSummaries.mergeTransactionSummaries(transactionSummaries, 0);
            }
        }
        return mergedTransactionErrorSummaries.getResult(sortOrder, limit);
    }
}
