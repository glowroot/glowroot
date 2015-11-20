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

import com.google.common.collect.Lists;

import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.live.ImmutableOverallSummary;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.LiveResult;
import org.glowroot.common.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.AggregateRepository.OverallQuery;
import org.glowroot.storage.repo.AggregateRepository.SummarySortOrder;
import org.glowroot.storage.repo.AggregateRepository.TransactionQuery;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ImmutableOverallQuery;
import org.glowroot.storage.repo.ImmutableTransactionQuery;
import org.glowroot.storage.repo.MutableAggregate;
import org.glowroot.storage.repo.ProfileCollector;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TransactionSummaryCollector;
import org.glowroot.storage.repo.Utils;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

class TransactionCommonService {

    private final AggregateRepository aggregateRepository;
    private final LiveAggregateRepository liveAggregateRepository;
    private final ConfigRepository configRepository;

    TransactionCommonService(AggregateRepository aggregateRepository,
            LiveAggregateRepository liveAggregateRepository, ConfigRepository configRepository) {
        this.aggregateRepository = aggregateRepository;
        this.liveAggregateRepository = liveAggregateRepository;
        this.configRepository = configRepository;
    }

    // query.from() is non-inclusive
    OverallSummary readOverallSummary(OverallQuery query) throws Exception {
        return getMergedOverallSummary(query);
    }

    // query.from() is non-inclusive
    Result<TransactionSummary> readTransactionSummaries(OverallQuery query,
            SummarySortOrder sortOrder, int limit) throws Exception {
        return getMergedTransactionSummaries(query, sortOrder, limit);
    }

    // query.from() is INCLUSIVE
    List<OverviewAggregate> getOverviewAggregates(TransactionQuery query, long liveCaptureTime)
            throws Exception {
        LiveResult<OverviewAggregate> liveResult =
                liveAggregateRepository.getLiveOverviewAggregates(query.transactionType(),
                        query.transactionName(), query.from(), query.to(), liveCaptureTime);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? query.to() : liveResult.initialCaptureTime() - 1;
        List<OverviewAggregate> aggregates =
                aggregateRepository.readOverviewAggregates(ImmutableTransactionQuery.builder()
                        .copyFrom(query)
                        .to(revisedTo)
                        .build());
        if (query.rollupLevel() == 0) {
            aggregates = Lists.newArrayList(aggregates);
            if (liveResult != null) {
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = query.from();
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<OverviewAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        orderedNonRolledUpAggregates.addAll(
                aggregateRepository.readOverviewAggregates(ImmutableTransactionQuery.builder()
                        .copyFrom(query)
                        .from(nonRolledUpFrom)
                        .to(revisedTo)
                        .rollupLevel(0)
                        .build()));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpOverviewAggregates(orderedNonRolledUpAggregates, liveCaptureTime,
                query.rollupLevel()));
        return aggregates;
    }

    // query.from() is INCLUSIVE
    List<PercentileAggregate> getPercentileAggregates(TransactionQuery query, long liveCaptureTime)
            throws Exception {
        LiveResult<PercentileAggregate> liveResult =
                liveAggregateRepository.getLivePercentileAggregates(query.transactionType(),
                        query.transactionName(), query.from(), query.to(), liveCaptureTime);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? query.to() : liveResult.initialCaptureTime() - 1;
        List<PercentileAggregate> aggregates =
                aggregateRepository.readPercentileAggregates(ImmutableTransactionQuery.builder()
                        .copyFrom(query)
                        .to(revisedTo)
                        .build());
        if (query.rollupLevel() == 0) {
            aggregates = Lists.newArrayList(aggregates);
            if (liveResult != null) {
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = query.from();
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<PercentileAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        orderedNonRolledUpAggregates.addAll(
                aggregateRepository.readPercentileAggregates(ImmutableTransactionQuery.builder()
                        .copyFrom(query)
                        .from(nonRolledUpFrom)
                        .to(revisedTo)
                        .rollupLevel(0)
                        .build()));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpPercentileAggregates(orderedNonRolledUpAggregates, liveCaptureTime,
                query.rollupLevel()));
        return aggregates;
    }

    // query.from() is INCLUSIVE
    List<ThroughputAggregate> getThroughputAggregates(TransactionQuery query, long liveCaptureTime)
            throws Exception {
        LiveResult<ThroughputAggregate> liveResult =
                liveAggregateRepository.getLiveThroughputAggregates(query.transactionType(),
                        query.transactionName(), query.from(), query.to(), liveCaptureTime);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? query.to() : liveResult.initialCaptureTime() - 1;
        List<ThroughputAggregate> aggregates =
                aggregateRepository.readThroughputAggregates(ImmutableTransactionQuery.builder()
                        .copyFrom(query)
                        .to(revisedTo)
                        .build());
        if (query.rollupLevel() == 0) {
            aggregates = Lists.newArrayList(aggregates);
            if (liveResult != null) {
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = query.from();
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<ThroughputAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        orderedNonRolledUpAggregates.addAll(
                aggregateRepository.readThroughputAggregates(ImmutableTransactionQuery.builder()
                        .copyFrom(query)
                        .from(nonRolledUpFrom)
                        .to(revisedTo)
                        .rollupLevel(0)
                        .build()));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpThroughputAggregates(orderedNonRolledUpAggregates, liveCaptureTime,
                query.rollupLevel()));
        return aggregates;
    }

    // query.from() is non-inclusive
    MutableProfile getMergedProfile(TransactionQuery query, List<String> includes,
            List<String> excludes, double truncateBranchPercentage) throws Exception {
        MutableProfile profile = getMergedProfile(query);
        if (!includes.isEmpty() || !excludes.isEmpty()) {
            profile.filter(includes, excludes);
        }
        if (truncateBranchPercentage != 0) {
            int minSamples =
                    (int) Math.ceil(profile.getSampleCount() * truncateBranchPercentage / 100);
            // don't truncate any root nodes
            profile.truncateBranches(minSamples);
        }
        return profile;
    }

    // query.from() is non-inclusive
    List<Aggregate.QueriesByType> getMergedQueries(TransactionQuery query) throws Exception {
        return getMergedQueries(query, getMaxAggregateQueriesPerQueryType(query.serverRollup()));
    }

    private OverallSummary getMergedOverallSummary(OverallQuery query) throws Exception {
        LiveResult<OverallSummary> liveResult = liveAggregateRepository
                .getLiveOverallSummary(query.transactionType(), query.from(), query.to());
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? query.to() : liveResult.initialCaptureTime() - 1;
        long revisedFrom = query.from();
        double totalNanos = 0;
        long transactionCount = 0;
        long lastCaptureTime = 0;
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            OverallSummary overallSummary = aggregateRepository.readOverallSummary(revisedQuery);
            totalNanos += overallSummary.totalNanos();
            transactionCount += overallSummary.transactionCount();
            lastCaptureTime = overallSummary.lastCaptureTime();
            long lastRolledUpTime = overallSummary.lastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        if (liveResult != null) {
            for (OverallSummary overallSummary : liveResult.get()) {
                totalNanos += overallSummary.totalNanos();
                transactionCount += overallSummary.transactionCount();
                // live results are ordered so no need for Math.max() here
                lastCaptureTime = overallSummary.lastCaptureTime();
            }
        }
        return ImmutableOverallSummary.builder()
                .totalNanos(totalNanos)
                .transactionCount(transactionCount)
                .lastCaptureTime(lastCaptureTime)
                .build();
    }

    private Result<TransactionSummary> getMergedTransactionSummaries(OverallQuery query,
            SummarySortOrder sortOrder, int limit) throws Exception {
        LiveResult<List<TransactionSummary>> liveResult = liveAggregateRepository
                .getLiveTransactionSummaries(query.transactionType(), query.from(), query.to());
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? query.to() : liveResult.initialCaptureTime() - 1;
        long revisedFrom = query.from();
        TransactionSummaryCollector mergedTransactionSummaries = new TransactionSummaryCollector();
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeInTransactionSummaries(mergedTransactionSummaries,
                    revisedQuery, sortOrder, limit);
            long lastRolledUpTime = mergedTransactionSummaries.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        if (liveResult != null) {
            for (List<TransactionSummary> transactionSummaries : liveResult.get()) {
                // second arg (lastCaptureTime) doesn't matter any more (it was only needed above)
                mergedTransactionSummaries.mergeTransactionSummaries(transactionSummaries, 0);
            }
        }
        return mergedTransactionSummaries.getResult(sortOrder, limit);
    }

    private MutableProfile getMergedProfile(TransactionQuery query) throws Exception {
        LiveResult<Profile> liveResult = liveAggregateRepository.getLiveProfile(
                query.transactionType(), query.transactionName(), query.from(), query.to());
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? query.to() : liveResult.initialCaptureTime() - 1;
        long revisedFrom = query.from();
        ProfileCollector mergedProfile = new ProfileCollector();
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeInProfiles(mergedProfile, revisedQuery);
            long lastRolledUpTime = mergedProfile.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        if (liveResult != null) {
            for (Profile profile : liveResult.get()) {
                mergedProfile.mergeProfile(profile);
            }
        }
        return mergedProfile.getProfile();
    }

    private List<Aggregate.QueriesByType> getMergedQueries(TransactionQuery query,
            int maxAggregateQueriesPerQueryType) throws Exception {
        LiveResult<List<Aggregate.QueriesByType>> liveResult =
                liveAggregateRepository.getLiveQueries(query.transactionType(),
                        query.transactionName(), query.from(), query.to());
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? query.to() : liveResult.initialCaptureTime() - 1;
        long revisedFrom = query.from();
        QueryCollector mergedQueries = new QueryCollector(maxAggregateQueriesPerQueryType, 0);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeInQueries(mergedQueries, revisedQuery);
            long lastRolledUpTime = mergedQueries.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        if (liveResult != null) {
            for (List<Aggregate.QueriesByType> queries : liveResult.get()) {
                mergedQueries.mergeQueries(queries);
            }
        }
        return mergedQueries.toProtobuf(true);
    }

    private List<OverviewAggregate> rollUpOverviewAggregates(
            List<OverviewAggregate> orderedNonRolledUpOverviewAggregates, long liveCaptureTime,
            int rollupLevel) throws Exception {
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        List<OverviewAggregate> rolledUpOverviewAggregates = Lists.newArrayList();
        MutableAggregate currMergedAggregate = null;
        long currRollupTime = Long.MIN_VALUE;
        for (OverviewAggregate nonRolledUpOverviewAggregate : orderedNonRolledUpOverviewAggregates) {
            long rollupTime = Utils.getNextRollupTime(nonRolledUpOverviewAggregate.captureTime(),
                    fixedIntervalMillis);
            if (rollupTime != currRollupTime && currMergedAggregate != null) {
                rolledUpOverviewAggregates.add(currMergedAggregate
                        .toOverviewAggregate(Math.min(currRollupTime, liveCaptureTime)));
                currMergedAggregate = new MutableAggregate(0);
            }
            if (currMergedAggregate == null) {
                currMergedAggregate = new MutableAggregate(0);
            }
            currRollupTime = rollupTime;
            currMergedAggregate.addTotalNanos(nonRolledUpOverviewAggregate.totalNanos());
            currMergedAggregate
                    .addTransactionCount(nonRolledUpOverviewAggregate.transactionCount());
            currMergedAggregate.addTotalCpuNanos(nonRolledUpOverviewAggregate.totalCpuNanos());
            currMergedAggregate
                    .addTotalBlockedNanos(nonRolledUpOverviewAggregate.totalBlockedNanos());
            currMergedAggregate
                    .addTotalWaitedNanos(nonRolledUpOverviewAggregate.totalWaitedNanos());
            currMergedAggregate
                    .addTotalAllocatedBytes(nonRolledUpOverviewAggregate.totalAllocatedBytes());
            currMergedAggregate.mergeRootTimers(nonRolledUpOverviewAggregate.rootTimers());
        }
        if (currMergedAggregate != null) {
            // roll up final one
            rolledUpOverviewAggregates.add(currMergedAggregate
                    .toOverviewAggregate(Math.min(currRollupTime, liveCaptureTime)));
        }
        return rolledUpOverviewAggregates;
    }

    private List<PercentileAggregate> rollUpPercentileAggregates(
            List<PercentileAggregate> orderedNonRolledUpPercentileAggregates, long liveCaptureTime,
            int rollupLevel) throws Exception {
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        List<PercentileAggregate> rolledUpPercentileAggregates = Lists.newArrayList();
        MutableAggregate currMergedAggregate = null;
        long currRollupTime = Long.MIN_VALUE;
        for (PercentileAggregate nonRolledUpPercentileAggregate : orderedNonRolledUpPercentileAggregates) {
            long rollupTime = Utils.getNextRollupTime(nonRolledUpPercentileAggregate.captureTime(),
                    fixedIntervalMillis);
            if (rollupTime != currRollupTime && currMergedAggregate != null) {
                rolledUpPercentileAggregates.add(currMergedAggregate
                        .toPercentileAggregate(Math.min(currRollupTime, liveCaptureTime)));
                currMergedAggregate = new MutableAggregate(0);
            }
            if (currMergedAggregate == null) {
                currMergedAggregate = new MutableAggregate(0);
            }
            currRollupTime = rollupTime;
            currMergedAggregate.addTotalNanos(nonRolledUpPercentileAggregate.totalNanos());
            currMergedAggregate
                    .addTransactionCount(nonRolledUpPercentileAggregate.transactionCount());
            currMergedAggregate.mergeHistogram(nonRolledUpPercentileAggregate.histogram());
        }
        if (currMergedAggregate != null) {
            // roll up final one
            rolledUpPercentileAggregates.add(currMergedAggregate
                    .toPercentileAggregate(Math.min(currRollupTime, liveCaptureTime)));
        }
        return rolledUpPercentileAggregates;
    }

    private List<ThroughputAggregate> rollUpThroughputAggregates(
            List<ThroughputAggregate> orderedNonRolledUpThroughputAggregates, long liveCaptureTime,
            int rollupLevel) throws Exception {
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        List<ThroughputAggregate> rolledUpThroughputAggregates = Lists.newArrayList();
        long currTransactionCount = 0;
        long currRollupTime = Long.MIN_VALUE;
        for (ThroughputAggregate nonRolledUpThroughputAggregate : orderedNonRolledUpThroughputAggregates) {
            long rollupTime = Utils.getNextRollupTime(nonRolledUpThroughputAggregate.captureTime(),
                    fixedIntervalMillis);
            if (rollupTime != currRollupTime && currTransactionCount > 0) {
                rolledUpThroughputAggregates.add(ImmutableThroughputAggregate
                        .of(Math.min(currRollupTime, liveCaptureTime), currTransactionCount));
                currTransactionCount = 0;
            }
            currRollupTime = rollupTime;
            currTransactionCount += nonRolledUpThroughputAggregate.transactionCount();
        }
        if (currTransactionCount > 0) {
            // roll up final one
            rolledUpThroughputAggregates.add(ImmutableThroughputAggregate
                    .of(Math.min(currRollupTime, liveCaptureTime), currTransactionCount));
        }
        return rolledUpThroughputAggregates;
    }

    private int getMaxAggregateQueriesPerQueryType(String serverRollup) {
        if (!serverRollup.equals("")) {
            // TODO this is hacky
            return ImmutableAdvancedConfig.builder().build().maxAggregateQueriesPerQueryType();
        }
        return configRepository.getAdvancedConfig(serverRollup).maxAggregateQueriesPerQueryType();
    }

}
