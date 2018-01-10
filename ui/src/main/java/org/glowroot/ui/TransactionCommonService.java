/*
 * Copyright 2014-2018 the original author or authors.
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

import com.google.common.base.Function;
import com.google.common.collect.Lists;

import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.live.ImmutableOverallQuery;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.LiveResult;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector.OverallSummary;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector.TransactionSummary;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.AgentConfigNotFoundException;
import org.glowroot.common.repo.MutableAggregate;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

class TransactionCommonService {

    private final AggregateRepository aggregateRepository;
    private final LiveAggregateRepository liveAggregateRepository;
    private final ConfigRepository configRepository;
    private final Clock clock;

    TransactionCommonService(AggregateRepository aggregateRepository,
            LiveAggregateRepository liveAggregateRepository, ConfigRepository configRepository,
            Clock clock) {
        this.aggregateRepository = aggregateRepository;
        this.liveAggregateRepository = liveAggregateRepository;
        this.configRepository = configRepository;
        this.clock = clock;
    }

    // query.from() is non-inclusive
    OverallSummary readOverallSummary(String agentRollupId, OverallQuery query, boolean autoRefresh)
            throws Exception {
        OverallSummaryCollector collector = new OverallSummaryCollector();
        long revisedFrom = query.from();
        long revisedTo;
        if (autoRefresh) {
            revisedTo = query.to();
        } else {
            revisedTo =
                    liveAggregateRepository.mergeInOverallSummary(agentRollupId, query, collector);
        }
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeOverallSummaryInto(agentRollupId, revisedQuery, collector);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getOverallSummary();
    }

    // query.from() is non-inclusive
    Result<TransactionSummary> readTransactionSummaries(String agentRollupId, OverallQuery query,
            SummarySortOrder sortOrder, int limit, boolean autoRefresh) throws Exception {
        TransactionSummaryCollector collector = new TransactionSummaryCollector();
        long revisedFrom = query.from();
        long revisedTo;
        if (autoRefresh) {
            revisedTo = query.to();
        } else {
            revisedTo = liveAggregateRepository.mergeInTransactionSummaries(agentRollupId, query,
                    collector);
        }
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeTransactionSummariesInto(agentRollupId, revisedQuery,
                    sortOrder, limit, collector);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getResult(sortOrder, limit);
    }

    // query.from() is INCLUSIVE
    List<OverviewAggregate> getOverviewAggregates(String agentRollupId, TransactionQuery query,
            boolean autoRefresh) throws Exception {
        LiveResult<OverviewAggregate> liveResult;
        long revisedTo;
        if (autoRefresh) {
            liveResult = null;
            revisedTo = query.to();
        } else {
            liveResult = liveAggregateRepository.getOverviewAggregates(agentRollupId, query);
            revisedTo = liveResult == null ? query.to() : liveResult.revisedTo();
        }
        TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<OverviewAggregate> aggregates =
                aggregateRepository.readOverviewAggregates(agentRollupId, revisedQuery);
        if (revisedQuery.rollupLevel() == 0) {
            if (liveResult != null) {
                aggregates = Lists.newArrayList(aggregates);
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = revisedQuery.from();
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<OverviewAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        if (nonRolledUpFrom <= revisedTo) {
            orderedNonRolledUpAggregates.addAll(
                    aggregateRepository.readOverviewAggregates(agentRollupId,
                            ImmutableTransactionQuery.builder()
                                    .copyFrom(revisedQuery)
                                    .from(nonRolledUpFrom)
                                    .rollupLevel(0)
                                    .build()));
        }
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        long fixedIntervalMillis = configRepository.getRollupConfigs()
                .get(revisedQuery.rollupLevel()).intervalMillis();
        aggregates.addAll(rollUpOverviewAggregates(orderedNonRolledUpAggregates,
                new RollupCaptureTimeFn(fixedIntervalMillis)));
        if (aggregates.size() >= 2) {
            long currentTime = clock.currentTimeMillis();
            OverviewAggregate nextToLastAggregate = aggregates.get(aggregates.size() - 2);
            if (currentTime - nextToLastAggregate.captureTime() < 60000) {
                aggregates.remove(aggregates.size() - 1);
            }
        }
        return aggregates;
    }

    // query.from() is INCLUSIVE
    List<PercentileAggregate> getPercentileAggregates(String agentRollupId, TransactionQuery query,
            boolean autoRefresh) throws Exception {
        LiveResult<PercentileAggregate> liveResult;
        long revisedTo;
        if (autoRefresh) {
            liveResult = null;
            revisedTo = query.to();
        } else {
            liveResult = liveAggregateRepository.getPercentileAggregates(agentRollupId, query);
            revisedTo = liveResult == null ? query.to() : liveResult.revisedTo();
        }
        TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<PercentileAggregate> aggregates =
                aggregateRepository.readPercentileAggregates(agentRollupId, revisedQuery);
        if (revisedQuery.rollupLevel() == 0) {
            if (liveResult != null) {
                aggregates = Lists.newArrayList(aggregates);
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = revisedQuery.from();
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<PercentileAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        if (nonRolledUpFrom <= revisedTo) {
            orderedNonRolledUpAggregates.addAll(
                    aggregateRepository.readPercentileAggregates(agentRollupId,
                            ImmutableTransactionQuery.builder()
                                    .copyFrom(revisedQuery)
                                    .from(nonRolledUpFrom)
                                    .rollupLevel(0)
                                    .build()));
        }
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        long fixedIntervalMillis = configRepository.getRollupConfigs()
                .get(revisedQuery.rollupLevel()).intervalMillis();
        aggregates.addAll(rollUpPercentileAggregates(orderedNonRolledUpAggregates,
                new RollupCaptureTimeFn(fixedIntervalMillis)));
        if (aggregates.size() >= 2) {
            long currentTime = clock.currentTimeMillis();
            PercentileAggregate nextToLastAggregate = aggregates.get(aggregates.size() - 2);
            if (currentTime - nextToLastAggregate.captureTime() < 60000) {
                aggregates.remove(aggregates.size() - 1);
            }
        }
        return aggregates;
    }

    // query.from() is INCLUSIVE
    List<ThroughputAggregate> getThroughputAggregates(String agentRollupId, TransactionQuery query,
            boolean autoRefresh) throws Exception {
        LiveResult<ThroughputAggregate> liveResult;
        long revisedTo;
        if (autoRefresh) {
            liveResult = null;
            revisedTo = query.to();
        } else {
            liveResult = liveAggregateRepository.getThroughputAggregates(agentRollupId, query);
            revisedTo = liveResult == null ? query.to() : liveResult.revisedTo();
        }
        TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<ThroughputAggregate> aggregates =
                aggregateRepository.readThroughputAggregates(agentRollupId, revisedQuery);
        if (revisedQuery.rollupLevel() == 0) {
            if (liveResult != null) {
                aggregates = Lists.newArrayList(aggregates);
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = revisedQuery.from();
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<ThroughputAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        if (nonRolledUpFrom <= revisedTo) {
            orderedNonRolledUpAggregates.addAll(aggregateRepository
                    .readThroughputAggregates(agentRollupId,
                            ImmutableTransactionQuery.builder()
                                    .copyFrom(revisedQuery)
                                    .from(nonRolledUpFrom)
                                    .rollupLevel(0)
                                    .build()));
        }
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        long fixedIntervalMillis = configRepository.getRollupConfigs()
                .get(revisedQuery.rollupLevel()).intervalMillis();
        aggregates.addAll(rollUpThroughputAggregates(orderedNonRolledUpAggregates,
                new RollupCaptureTimeFn(fixedIntervalMillis)));
        if (aggregates.size() >= 2) {
            long currentTime = clock.currentTimeMillis();
            ThroughputAggregate nextToLastAggregate = aggregates.get(aggregates.size() - 2);
            if (currentTime - nextToLastAggregate.captureTime() < 60000) {
                aggregates.remove(aggregates.size() - 1);
            }
        }
        return aggregates;
    }

    // query.from() is non-inclusive
    Map<String, List<MutableQuery>> getMergedQueries(String agentRollupId,
            TransactionQuery query) throws Exception {
        int maxAggregateQueriesPerType = getMaxAggregateQueriesPerType(agentRollupId);
        QueryCollector queryCollector = new QueryCollector(maxAggregateQueriesPerType);
        long revisedFrom = query.from();
        long revisedTo =
                liveAggregateRepository.mergeInQueries(agentRollupId, query, queryCollector);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeQueriesInto(agentRollupId, revisedQuery, queryCollector);
            long lastRolledUpTime = queryCollector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return queryCollector.getSortedAndTruncatedQueries();
    }

    @Nullable
    String readFullQueryText(String agentRollupId, String fullQueryTextSha1) throws Exception {
        // checking live data is not efficient since must perform many sha1 hashes
        // so check repository first
        String fullQueryText =
                aggregateRepository.readFullQueryText(agentRollupId, fullQueryTextSha1);
        if (fullQueryText != null) {
            return fullQueryText;
        }
        return liveAggregateRepository.getFullQueryText(agentRollupId, fullQueryTextSha1);
    }

    // query.from() is non-inclusive
    List<Aggregate.ServiceCallsByType> getMergedServiceCalls(String agentRollupId,
            TransactionQuery query)
            throws Exception {
        int maxAggregateServiceCallsPerType =
                getMaxAggregateServiceCallsPerType(agentRollupId);
        ServiceCallCollector serviceCallCollector =
                new ServiceCallCollector(maxAggregateServiceCallsPerType, 0);
        long revisedFrom = query.from();
        long revisedTo = liveAggregateRepository.mergeInServiceCalls(agentRollupId, query,
                serviceCallCollector);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeServiceCallsInto(agentRollupId, revisedQuery,
                    serviceCallCollector);
            long lastRolledUpTime = serviceCallCollector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return serviceCallCollector.toProto();
    }

    // query.from() is non-inclusive
    MutableProfile getMergedProfile(String agentRollupId, TransactionQuery query, boolean auxiliary,
            List<String> includes, List<String> excludes, double truncateBranchPercentage)
            throws Exception {
        MutableProfile profile = getMergedProfile(agentRollupId, query, auxiliary);
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

    boolean hasMainThreadProfile(String agentRollupId, TransactionQuery query) throws Exception {
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .rollupLevel(rollupLevel)
                    .build();
            if (aggregateRepository.hasMainThreadProfile(agentRollupId, revisedQuery)) {
                return true;
            }
        }
        return false;
    }

    boolean hasAuxThreadProfile(String agentRollupId, TransactionQuery query) throws Exception {
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .rollupLevel(rollupLevel)
                    .build();
            if (aggregateRepository.hasAuxThreadProfile(agentRollupId, revisedQuery)) {
                return true;
            }
        }
        return false;
    }

    static List<OverviewAggregate> rollUpOverviewAggregates(
            List<OverviewAggregate> orderedNonRolledUpOverviewAggregates,
            Function<Long, Long> rollupCaptureTimeFn) {
        List<OverviewAggregate> rolledUpOverviewAggregates = Lists.newArrayList();
        MutableAggregate currMergedAggregate = new MutableAggregate(0, 0);
        long currRollupCaptureTime = Long.MIN_VALUE;
        long maxCaptureTime = Long.MIN_VALUE;
        for (OverviewAggregate nonRolledUpOverviewAggregate : orderedNonRolledUpOverviewAggregates) {
            maxCaptureTime = nonRolledUpOverviewAggregate.captureTime();
            long rollupCaptureTime = rollupCaptureTimeFn.apply(maxCaptureTime);
            if (rollupCaptureTime != currRollupCaptureTime && !currMergedAggregate.isEmpty()) {
                rolledUpOverviewAggregates
                        .add(currMergedAggregate.toOverviewAggregate(currRollupCaptureTime));
                currMergedAggregate = new MutableAggregate(0, 0);
            }
            currRollupCaptureTime = rollupCaptureTime;
            currMergedAggregate
                    .addTotalDurationNanos(nonRolledUpOverviewAggregate.totalDurationNanos());
            currMergedAggregate
                    .addTransactionCount(nonRolledUpOverviewAggregate.transactionCount());
            currMergedAggregate
                    .mergeMainThreadRootTimers(nonRolledUpOverviewAggregate.mainThreadRootTimers());
            currMergedAggregate
                    .mergeAuxThreadRootTimers(nonRolledUpOverviewAggregate.auxThreadRootTimers());
            currMergedAggregate
                    .mergeAsyncTimers(nonRolledUpOverviewAggregate.asyncTimers());
            currMergedAggregate
                    .mergeMainThreadStats(nonRolledUpOverviewAggregate.mainThreadStats());
            currMergedAggregate.mergeAuxThreadStats(nonRolledUpOverviewAggregate.auxThreadStats());
        }
        if (!currMergedAggregate.isEmpty()) {
            // roll up final one
            rolledUpOverviewAggregates.add(currMergedAggregate.toOverviewAggregate(maxCaptureTime));
        }
        return rolledUpOverviewAggregates;
    }

    static List<PercentileAggregate> rollUpPercentileAggregates(
            List<PercentileAggregate> orderedNonRolledUpPercentileAggregates,
            Function<Long, Long> rollupCaptureTimeFn) {
        List<PercentileAggregate> rolledUpPercentileAggregates = Lists.newArrayList();
        MutableAggregate currMergedAggregate = new MutableAggregate(0, 0);
        long currRollupCaptureTime = Long.MIN_VALUE;
        long maxCaptureTime = Long.MIN_VALUE;
        for (PercentileAggregate nonRolledUpPercentileAggregate : orderedNonRolledUpPercentileAggregates) {
            maxCaptureTime = nonRolledUpPercentileAggregate.captureTime();
            long rollupCaptureTime = rollupCaptureTimeFn.apply(maxCaptureTime);
            if (rollupCaptureTime != currRollupCaptureTime && !currMergedAggregate.isEmpty()) {
                rolledUpPercentileAggregates
                        .add(currMergedAggregate.toPercentileAggregate(currRollupCaptureTime));
                currMergedAggregate = new MutableAggregate(0, 0);
            }
            currRollupCaptureTime = rollupCaptureTime;
            currMergedAggregate
                    .addTotalDurationNanos(nonRolledUpPercentileAggregate.totalDurationNanos());
            currMergedAggregate
                    .addTransactionCount(nonRolledUpPercentileAggregate.transactionCount());
            currMergedAggregate.mergeDurationNanosHistogram(
                    nonRolledUpPercentileAggregate.durationNanosHistogram());
        }
        if (!currMergedAggregate.isEmpty()) {
            // roll up final one
            rolledUpPercentileAggregates
                    .add(currMergedAggregate.toPercentileAggregate(maxCaptureTime));
        }
        return rolledUpPercentileAggregates;
    }

    static List<ThroughputAggregate> rollUpThroughputAggregates(
            List<ThroughputAggregate> orderedNonRolledUpThroughputAggregates,
            Function<Long, Long> rollupCaptureTimeFn) {
        List<ThroughputAggregate> rolledUpThroughputAggregates = Lists.newArrayList();
        long currTransactionCount = 0;
        // error_count is null for data inserted prior to glowroot central 0.9.18
        // rolling up any interval with null error_count should result in null error_count
        boolean hasMissingErrorCount = false;
        long currErrorCount = 0;
        long currRollupCaptureTime = Long.MIN_VALUE;
        long maxCaptureTime = Long.MIN_VALUE;
        for (ThroughputAggregate nonRolledUpThroughputAggregate : orderedNonRolledUpThroughputAggregates) {
            maxCaptureTime = nonRolledUpThroughputAggregate.captureTime();
            long rollupCaptureTime = rollupCaptureTimeFn.apply(maxCaptureTime);
            if (rollupCaptureTime != currRollupCaptureTime && currTransactionCount > 0) {
                rolledUpThroughputAggregates.add(ImmutableThroughputAggregate.builder()
                        .captureTime(currRollupCaptureTime)
                        .transactionCount(currTransactionCount)
                        .errorCount(hasMissingErrorCount ? null : currErrorCount)
                        .build());
                currTransactionCount = 0;
                hasMissingErrorCount = false;
                currErrorCount = 0;
            }
            currRollupCaptureTime = rollupCaptureTime;
            currTransactionCount += nonRolledUpThroughputAggregate.transactionCount();
            Long errorCount = nonRolledUpThroughputAggregate.errorCount();
            if (errorCount == null) {
                hasMissingErrorCount = true;
            } else {
                currErrorCount += errorCount;
            }
        }
        if (currTransactionCount > 0) {
            // roll up final one
            rolledUpThroughputAggregates.add(ImmutableThroughputAggregate.builder()
                    .captureTime(maxCaptureTime)
                    .transactionCount(currTransactionCount)
                    .errorCount(hasMissingErrorCount ? null : currErrorCount)
                    .build());
        }
        return rolledUpThroughputAggregates;
    }

    private MutableProfile getMergedProfile(String agentRollupId, TransactionQuery query,
            boolean auxiliary) throws Exception {
        ProfileCollector collector = new ProfileCollector();
        long revisedFrom = query.from();
        long revisedTo;
        if (auxiliary) {
            revisedTo =
                    liveAggregateRepository.mergeInAuxThreadProfiles(agentRollupId, query,
                            collector);
        } else {
            revisedTo = liveAggregateRepository.mergeInMainThreadProfiles(agentRollupId, query,
                    collector);
        }
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            if (auxiliary) {
                aggregateRepository.mergeAuxThreadProfilesInto(agentRollupId, revisedQuery,
                        collector);
            } else {
                aggregateRepository.mergeMainThreadProfilesInto(agentRollupId, revisedQuery,
                        collector);
            }
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getProfile();
    }

    private int getMaxAggregateQueriesPerType(String agentRollupId) throws Exception {
        AdvancedConfig advancedConfig;
        try {
            advancedConfig = configRepository.getAdvancedConfig(agentRollupId);
        } catch (AgentConfigNotFoundException e) {
            return ConfigDefaults.ADVANCED_MAX_AGGREGATE_QUERIES_PER_TYPE;
        }
        if (advancedConfig.hasMaxAggregateQueriesPerType()) {
            return advancedConfig.getMaxAggregateQueriesPerType().getValue();
        } else {
            return ConfigDefaults.ADVANCED_MAX_AGGREGATE_QUERIES_PER_TYPE;
        }
    }

    private int getMaxAggregateServiceCallsPerType(String agentRollupId) throws Exception {
        AdvancedConfig advancedConfig;
        try {
            advancedConfig = configRepository.getAdvancedConfig(agentRollupId);
        } catch (AgentConfigNotFoundException e) {
            return ConfigDefaults.ADVANCED_MAX_AGGREGATE_SERVICE_CALLS_PER_TYPE;
        }
        if (advancedConfig.hasMaxAggregateServiceCallsPerType()) {
            return advancedConfig.getMaxAggregateServiceCallsPerType().getValue();
        } else {
            return ConfigDefaults.ADVANCED_MAX_AGGREGATE_SERVICE_CALLS_PER_TYPE;
        }
    }

    private static class RollupCaptureTimeFn implements Function<Long, Long> {

        private final long fixedIntervalMillis;

        private RollupCaptureTimeFn(long fixedIntervalMillis) {
            this.fixedIntervalMillis = fixedIntervalMillis;
        }

        @Override
        public Long apply(Long captureTime) {
            return Utils.getRollupCaptureTime(captureTime, fixedIntervalMillis);
        }
    }
}
