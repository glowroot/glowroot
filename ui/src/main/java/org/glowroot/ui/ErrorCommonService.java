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

import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.AggregateRepository.ErrorSummarySortOrder;
import org.glowroot.storage.repo.AggregateRepository.OverallErrorSummary;
import org.glowroot.storage.repo.AggregateRepository.OverallQuery;
import org.glowroot.storage.repo.AggregateRepository.TransactionErrorSummary;
import org.glowroot.storage.repo.ImmutableOverallErrorSummary;
import org.glowroot.storage.repo.ImmutableOverallQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TransactionErrorSummaryCollector;

class ErrorCommonService {

    private final AggregateRepository aggregateRepository;

    ErrorCommonService(AggregateRepository aggregateRepository) {
        this.aggregateRepository = aggregateRepository;
    }

    // from is non-inclusive
    OverallErrorSummary readOverallErrorSummary(OverallQuery query) throws Exception {
        long revisedFrom = query.from();
        long errorCount = 0;
        long transactionCount = 0;
        long lastCaptureTime = 0;
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(query.to())
                    .rollupLevel(rollupLevel)
                    .build();
            OverallErrorSummary overallSummary =
                    aggregateRepository.readOverallErrorSummary(revisedQuery);
            errorCount += overallSummary.errorCount();
            transactionCount += overallSummary.transactionCount();
            lastCaptureTime = overallSummary.lastCaptureTime();
            long lastRolledUpTime = overallSummary.lastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > query.to()) {
                break;
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
        long revisedFrom = query.from();
        TransactionErrorSummaryCollector mergedTransactionErrorSummaries =
                new TransactionErrorSummaryCollector();
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(query.to())
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeInTransactionErrorSummaries(mergedTransactionErrorSummaries,
                    revisedQuery, sortOrder, limit);
            long lastRolledUpTime = mergedTransactionErrorSummaries.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > query.to()) {
                break;
            }
        }
        return mergedTransactionErrorSummaries.getResult(sortOrder, limit);
    }
}
