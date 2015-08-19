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
package org.glowroot.common.live;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.glowroot.common.repo.AggregateRepository.ErrorPoint;
import org.glowroot.common.repo.AggregateRepository.OverallErrorSummary;
import org.glowroot.common.repo.AggregateRepository.OverallSummary;
import org.glowroot.common.repo.AggregateRepository.OverviewAggregate;
import org.glowroot.common.repo.AggregateRepository.PercentileAggregate;
import org.glowroot.common.repo.AggregateRepository.TransactionErrorSummary;
import org.glowroot.common.repo.AggregateRepository.TransactionSummary;
import org.glowroot.common.repo.MutableProfileNode;
import org.glowroot.common.repo.MutableQuery;

public interface LiveAggregateRepository {

    // non-inclusive
    @Nullable
    LiveResult<OverallSummary> getLiveOverallSummary(String transactionType, long from, long to)
            throws Exception;

    // non-inclusive
    @Nullable
    LiveResult<List<TransactionSummary>> getLiveTransactionSummaries(String transactionType,
            long from, long to) throws Exception;

    @Nullable
    LiveResult<OverviewAggregate> getLiveOverviewAggregates(String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception;

    @Nullable
    LiveResult<PercentileAggregate> getLivePercentileAggregates(String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception;

    @Nullable
    LiveResult<MutableProfileNode> getLiveProfile(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception;

    @Nullable
    LiveResult<Map<String, List<MutableQuery>>> getLiveQueries(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception;

    @Nullable
    LiveResult<OverallErrorSummary> getLiveOverallErrorSummary(String transactionType, long from,
            long to) throws Exception;

    @Nullable
    LiveResult<List<TransactionErrorSummary>> getLiveTransactionErrorSummaries(
            String transactionType, long from, long to) throws Exception;

    @Nullable
    LiveResult<ErrorPoint> getLiveErrorPoints(String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception;

    void clearAll();

    public class LiveResult<T> {

        private final List<T> result;
        private final long initialCaptureTime;

        public LiveResult(List<T> result, long initialCaptureTime) {
            this.result = result;
            this.initialCaptureTime = initialCaptureTime;
        }

        public List<T> get() {
            return result;
        }

        public long initialCaptureTime() {
            return initialCaptureTime;
        }
    }

    public class LiveAggregateRepositoryNop implements LiveAggregateRepository {

        @Override
        public @Nullable LiveResult<OverallSummary> getLiveOverallSummary(String transactionType,
                long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<List<TransactionSummary>> getLiveTransactionSummaries(
                String transactionType, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<OverviewAggregate> getLiveOverviewAggregates(
                String transactionType, @Nullable String transactionName, long from, long to,
                long liveCaptureTime) {
            return null;
        }

        @Override
        public @Nullable LiveResult<PercentileAggregate> getLivePercentileAggregates(
                String transactionType, @Nullable String transactionName, long from, long to,
                long liveCaptureTime) {
            return null;
        }

        @Override
        public @Nullable LiveResult<MutableProfileNode> getLiveProfile(String transactionType,
                @Nullable String transactionName, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<Map<String, List<MutableQuery>>> getLiveQueries(
                String transactionType, @Nullable String transactionName, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<OverallErrorSummary> getLiveOverallErrorSummary(
                String transactionType, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<List<TransactionErrorSummary>> getLiveTransactionErrorSummaries(
                String transactionType, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<ErrorPoint> getLiveErrorPoints(String transactionType,
                @Nullable String transactionName, long from, long to, long liveCaptureTime) {
            return null;
        }

        @Override
        public void clearAll() {}
    }
}
