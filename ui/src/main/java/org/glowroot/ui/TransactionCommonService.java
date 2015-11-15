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
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;

import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.live.ImmutableOverallSummary;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.ImmutableTransactionSummary;
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
import org.glowroot.storage.repo.AggregateRepository.TransactionSummaryQuery;
import org.glowroot.storage.repo.AggregateRepository.TransactionSummarySortOrder;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ImmutableTransactionSummaryQuery;
import org.glowroot.storage.repo.MutableAggregate;
import org.glowroot.storage.repo.ProfileCollector;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.Utils;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionCommonService {

    private static final Ordering<TransactionSummary> orderingByTotalTimeDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(TransactionSummary left, TransactionSummary right) {
                    return Doubles.compare(right.totalNanos(), left.totalNanos());
                }
            };

    private static final Ordering<TransactionSummary> orderingByAverageTimeDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(TransactionSummary left, TransactionSummary right) {
                    return Doubles.compare(right.totalNanos() / right.transactionCount(),
                            left.totalNanos() / left.transactionCount());
                }
            };

    private static final Ordering<TransactionSummary> orderingByTransactionCountDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(TransactionSummary left, TransactionSummary right) {
                    return Longs.compare(right.transactionCount(), left.transactionCount());
                }
            };

    private final AggregateRepository aggregateRepository;
    private final LiveAggregateRepository liveAggregateRepository;
    private final ConfigRepository configRepository;
    private final RollupLevelService rollupLevelService;

    TransactionCommonService(AggregateRepository aggregateRepository,
            LiveAggregateRepository liveAggregateRepository, ConfigRepository configRepository,
            RollupLevelService rollupLevelService) {
        this.aggregateRepository = aggregateRepository;
        this.liveAggregateRepository = liveAggregateRepository;
        this.configRepository = configRepository;
        this.rollupLevelService = rollupLevelService;
    }

    // from is non-inclusive
    OverallSummary readOverallSummary(String serverRollup, String transactionType, long from,
            long to) throws Exception {
        LiveResult<OverallSummary> liveResult = liveAggregateRepository
                .getLiveOverallSummary(serverRollup, transactionType, from, to);
        if (liveResult == null) {
            return aggregateRepository.readOverallSummary(serverRollup, transactionType, from, to);
        }
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult.initialCaptureTime() - 1;
        OverallSummary overallSummary = aggregateRepository.readOverallSummary(serverRollup,
                transactionType, from, revisedTo);
        for (OverallSummary liveOverallSummary : liveResult.get()) {
            overallSummary = combineOverallSummaries(overallSummary, liveOverallSummary);
        }
        return overallSummary;
    }

    // query.from() is non-inclusive
    Result<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws Exception {
        LiveResult<List<TransactionSummary>> liveResult =
                liveAggregateRepository.getLiveTransactionSummaries(query.serverRollup(),
                        query.transactionType(), query.from(), query.to());
        if (liveResult == null) {
            return aggregateRepository.readTransactionSummaries(query);
        }
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult.initialCaptureTime() - 1;
        TransactionSummaryQuery revisedQuery =
                ImmutableTransactionSummaryQuery.builder().copyFrom(query).to(revisedTo).build();
        Result<TransactionSummary> queryResult =
                aggregateRepository.readTransactionSummaries(revisedQuery);

        List<TransactionSummary> transactionSummaries =
                mergeInLiveTransactionSummaries(queryResult.records(), liveResult.get());

        // sort and truncate if necessary
        transactionSummaries = sortTransactionSummaries(transactionSummaries, query.sortOrder());
        boolean moreAvailable = queryResult.moreAvailable();
        if (transactionSummaries.size() > query.limit()) {
            moreAvailable = true;
            transactionSummaries = transactionSummaries.subList(0, query.limit());
        }
        return new Result<TransactionSummary>(transactionSummaries, moreAvailable);
    }

    // from is non-inclusive
    boolean shouldHaveQueries(String serverRollup, String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        if (transactionName == null) {
            return aggregateRepository.shouldHaveOverallQueries(serverRollup, transactionType, from,
                    to);
        } else {
            return aggregateRepository.shouldHaveTransactionQueries(serverRollup, transactionType,
                    transactionName, from, to);
        }
    }

    // from is non-inclusive
    boolean shouldHaveProfile(String serverRollup, String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        if (transactionName == null) {
            return aggregateRepository.shouldHaveOverallProfile(serverRollup, transactionType, from,
                    to);
        } else {
            return aggregateRepository.shouldHaveTransactionProfile(serverRollup, transactionType,
                    transactionName, from, to);
        }
    }

    // from is INCLUSIVE
    List<OverviewAggregate> getOverviewAggregates(String serverRollup, String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(from, to);
        LiveResult<OverviewAggregate> liveResult =
                liveAggregateRepository.getLiveOverviewAggregates(serverRollup, transactionType,
                        transactionName, from - 1, to, liveCaptureTime);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        List<OverviewAggregate> aggregates = getOverviewAggregatesFromDao(serverRollup,
                transactionType, transactionName, from, revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            aggregates = Lists.newArrayList(aggregates);
            if (liveResult != null) {
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = from;
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<OverviewAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        orderedNonRolledUpAggregates.addAll(getOverviewAggregatesFromDao(serverRollup,
                transactionType, transactionName, nonRolledUpFrom, revisedTo, 0));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpOverviewAggregates(orderedNonRolledUpAggregates, liveCaptureTime,
                rollupLevel));
        return aggregates;
    }

    // from is INCLUSIVE
    List<PercentileAggregate> getPercentileAggregates(String serverRollup, String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(from, to);
        LiveResult<PercentileAggregate> liveResult =
                liveAggregateRepository.getLivePercentileAggregates(serverRollup, transactionType,
                        transactionName, from - 1, to, liveCaptureTime);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        List<PercentileAggregate> aggregates = getPercentileAggregatesFromDao(serverRollup,
                transactionType, transactionName, from, revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            aggregates = Lists.newArrayList(aggregates);
            if (liveResult != null) {
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = from;
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<PercentileAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        orderedNonRolledUpAggregates.addAll(getPercentileAggregatesFromDao(serverRollup,
                transactionType, transactionName, nonRolledUpFrom, revisedTo, 0));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpPercentileAggregates(orderedNonRolledUpAggregates, liveCaptureTime,
                rollupLevel));
        return aggregates;
    }

    // from is INCLUSIVE
    List<ThroughputAggregate> getThroughputAggregates(String serverRollup, String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(from, to);
        LiveResult<ThroughputAggregate> liveResult =
                liveAggregateRepository.getLiveThroughputAggregates(serverRollup, transactionType,
                        transactionName, from - 1, to, liveCaptureTime);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        List<ThroughputAggregate> aggregates = getThroughputAggregatesFromDao(serverRollup,
                transactionType, transactionName, from, revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            aggregates = Lists.newArrayList(aggregates);
            if (liveResult != null) {
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = from;
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<ThroughputAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        orderedNonRolledUpAggregates.addAll(getThroughputAggregatesFromDao(serverRollup,
                transactionType, transactionName, nonRolledUpFrom, revisedTo, 0));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpThroughputAggregates(orderedNonRolledUpAggregates, liveCaptureTime,
                rollupLevel));
        return aggregates;
    }

    // from is non-inclusive
    MutableProfile getMergedProfile(String serverRollup, String transactionType,
            @Nullable String transactionName, long from, long to, List<String> includes,
            List<String> excludes, double truncateBranchPercentage) throws Exception {
        MutableProfile profile =
                getMergedProfile(serverRollup, transactionType, transactionName, from, to);
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

    // from is non-inclusive
    List<Aggregate.QueriesByType> getMergedQueries(String serverRollup, String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        return getMergedQueries(serverRollup, transactionType, transactionName, from, to,
                getMaxAggregateQueriesPerQueryType(serverRollup));
    }

    // from is INCLUSIVE
    private List<OverviewAggregate> getOverviewAggregatesFromDao(String serverRollup,
            String transactionType, @Nullable String transactionName, long from, long to,
            int rollupLevel) throws Exception {
        if (transactionName == null) {
            return aggregateRepository.readOverallOverviewAggregates(serverRollup, transactionType,
                    from, to, rollupLevel);
        } else {
            return aggregateRepository.readTransactionOverviewAggregates(serverRollup,
                    transactionType, transactionName, from, to, rollupLevel);
        }
    }

    // from is INCLUSIVE
    private List<PercentileAggregate> getPercentileAggregatesFromDao(String serverRollup,
            String transactionType, @Nullable String transactionName, long from, long to,
            int rollupLevel) throws Exception {
        if (transactionName == null) {
            return aggregateRepository.readOverallPercentileAggregates(serverRollup,
                    transactionType, from, to, rollupLevel);
        } else {
            return aggregateRepository.readTransactionPercentileAggregates(serverRollup,
                    transactionType, transactionName, from, to, rollupLevel);
        }
    }

    // from is INCLUSIVE
    private List<ThroughputAggregate> getThroughputAggregatesFromDao(String serverRollup,
            String transactionType, @Nullable String transactionName, long from, long to,
            int rollupLevel) throws Exception {
        if (transactionName == null) {
            return aggregateRepository.readOverallThroughputAggregates(serverRollup,
                    transactionType, from, to, rollupLevel);
        } else {
            return aggregateRepository.readTransactionThroughputAggregates(serverRollup,
                    transactionType, transactionName, from, to, rollupLevel);
        }
    }

    // this method may return some rolled up profile aggregates and some non-rolled up
    // they are all distinct though
    // this is ok since the results of this method are currently just aggregated into single
    // result as opposed to charted over time period
    //
    // from is non-inclusive
    private MutableProfile getMergedProfile(String serverRollup, String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        int initialRollupLevel = rollupLevelService.getRollupLevelForView(from, to);
        LiveResult<Profile> liveResult = liveAggregateRepository
                .getLiveProfile(serverRollup, transactionType, transactionName, from, to);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        long revisedFrom = from;
        ProfileCollector mergedProfile = new ProfileCollector();
        for (int rollupLevel = initialRollupLevel; rollupLevel >= 0; rollupLevel--) {
            mergeInProfileFromDao(mergedProfile, serverRollup, transactionType, transactionName,
                    revisedFrom, revisedTo, rollupLevel);
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

    // this method may return some rolled up query aggregates and some non-rolled up
    // they are all distinct though
    // this is ok since the results of this method are currently just aggregated into single
    // result as opposed to charted over time period
    //
    // from is non-inclusive
    private List<Aggregate.QueriesByType> getMergedQueries(String serverRollup,
            String transactionType, @Nullable String transactionName, long from, long to,
            int maxAggregateQueriesPerQueryType) throws Exception {
        int initialRollupLevel = rollupLevelService.getRollupLevelForView(from, to);
        LiveResult<List<Aggregate.QueriesByType>> liveResult = liveAggregateRepository
                .getLiveQueries(serverRollup, transactionType, transactionName, from, to);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        long revisedFrom = from;
        QueryCollector mergedQueries = new QueryCollector(maxAggregateQueriesPerQueryType, 0);
        for (int rollupLevel = initialRollupLevel; rollupLevel >= 0; rollupLevel--) {
            mergeInQueriesFromDao(mergedQueries, serverRollup, transactionType, transactionName,
                    from, revisedTo, rollupLevel);
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

    // from is non-inclusive
    private void mergeInProfileFromDao(ProfileCollector mergedProfile, String serverRollup,
            String transactionType, @Nullable String transactionName, long from, long to,
            int rollupLevel) throws Exception {
        if (transactionName == null) {
            aggregateRepository.mergeInOverallProfiles(mergedProfile, serverRollup, transactionType,
                    from, to, rollupLevel);
        } else {
            aggregateRepository.mergeInTransactionProfiles(mergedProfile, serverRollup,
                    transactionType, transactionName, from, to, rollupLevel);
        }
    }

    // from is non-inclusive
    private void mergeInQueriesFromDao(QueryCollector mergedQueries, String serverRollup,
            String transactionType, @Nullable String transactionName, long from, long to,
            int rollupLevel) throws Exception {
        if (transactionName == null) {
            aggregateRepository.mergeInOverallQueries(mergedQueries, serverRollup, transactionType,
                    from, to, rollupLevel);
        } else {
            aggregateRepository.mergeInTransactionQueries(mergedQueries, serverRollup,
                    transactionType, transactionName, from, to, rollupLevel);
        }
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
            if (rollupTime != currRollupTime && currTransactionCount != 0) {
                rolledUpThroughputAggregates.add(ImmutableThroughputAggregate
                        .of(Math.min(currRollupTime, liveCaptureTime), currTransactionCount));
                currTransactionCount = 0;
            }
            currRollupTime = rollupTime;
            currTransactionCount += nonRolledUpThroughputAggregate.transactionCount();
        }
        if (currTransactionCount != 0) {
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

    private static List<TransactionSummary> mergeInLiveTransactionSummaries(
            List<TransactionSummary> transactionSummaries,
            List<List<TransactionSummary>> liveTransactionSummaries) {
        Map<String, TransactionSummary> transactionSummaryMap = Maps.newHashMap();
        for (TransactionSummary transactionSummary : transactionSummaries) {
            String transactionName = transactionSummary.transactionName();
            // transaction name is only null for overall summary
            checkNotNull(transactionName);
            transactionSummaryMap.put(transactionName, transactionSummary);
        }
        for (List<TransactionSummary> mid : liveTransactionSummaries) {
            for (TransactionSummary liveTransactionSummary : mid) {
                String transactionName = liveTransactionSummary.transactionName();
                // transaction name is only null for overall summary
                checkNotNull(transactionName);
                TransactionSummary transactionSummary = transactionSummaryMap.get(transactionName);
                if (transactionSummary == null) {
                    transactionSummaryMap.put(transactionName, liveTransactionSummary);
                } else {
                    transactionSummaryMap.put(transactionName, combineTransactionSummaries(
                            transactionName, transactionSummary, liveTransactionSummary));
                }
            }
        }
        return Lists.newArrayList(transactionSummaryMap.values());
    }

    private static OverallSummary combineOverallSummaries(OverallSummary summary1,
            OverallSummary summary2) {
        return ImmutableOverallSummary.builder()
                .totalNanos(summary1.totalNanos() + summary2.totalNanos())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
    }

    private static TransactionSummary combineTransactionSummaries(String transactionName,
            TransactionSummary summary1, TransactionSummary summary2) {
        return ImmutableTransactionSummary.builder()
                .transactionName(transactionName)
                .totalNanos(summary1.totalNanos() + summary2.totalNanos())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
    }

    private static List<TransactionSummary> sortTransactionSummaries(
            Iterable<TransactionSummary> transactionSummaries,
            TransactionSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case TOTAL_TIME:
                return orderingByTotalTimeDesc.immutableSortedCopy(transactionSummaries);
            case AVERAGE_TIME:
                return orderingByAverageTimeDesc.immutableSortedCopy(transactionSummaries);
            case THROUGHPUT:
                return orderingByTransactionCountDesc.immutableSortedCopy(transactionSummaries);
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }

}
