/*
 * Copyright 2014-2023 the original author or authors.
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

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common.ConfigDefaults;
import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutableSummaryQuery;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.LiveResult;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector.SummarySortOrder;
import org.glowroot.common.model.TransactionNameSummaryCollector.TransactionNameSummary;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.AggregateRepository;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.AgentConfigNotFoundException;
import org.glowroot.common2.repo.MutableAggregate;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

import static com.google.common.base.Preconditions.checkNotNull;

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
    OverallSummaryCollector readOverallSummary(String agentRollupId, SummaryQuery query,
            boolean autoRefresh) throws Exception {
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
            SummaryQuery revisedQuery = ImmutableSummaryQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeOverallSummaryInto(agentRollupId, revisedQuery, collector, CassandraProfile.web);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector;
    }

    // query.from() is non-inclusive
    Result<TransactionNameSummary> readTransactionNameSummaries(String agentRollupId,
                                                                SummaryQuery query, SummarySortOrder sortOrder, int limit, boolean autoRefresh, CassandraProfile profile)
            throws Exception {
        TransactionNameSummaryCollector collector = new TransactionNameSummaryCollector();
        long revisedFrom = query.from();
        long revisedTo;
        if (autoRefresh) {
            revisedTo = query.to();
        } else {
            revisedTo =
                    liveAggregateRepository.mergeInTransactionNameSummaries(agentRollupId, query,
                            collector);
        }
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            SummaryQuery revisedQuery = ImmutableSummaryQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeTransactionNameSummariesInto(agentRollupId, revisedQuery,
                    sortOrder, limit, collector, profile);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getResult(sortOrder, limit);
    }

    // query.from() is INCLUSIVE
    List<OverviewAggregate> getOverviewAggregates(String agentRollupId, AggregateQuery query,
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
        AggregateQuery revisedQuery = ImmutableAggregateQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<OverviewAggregate> aggregates =
                aggregateRepository.readOverviewAggregates(agentRollupId, revisedQuery, CassandraProfile.web);
        if (revisedQuery.rollupLevel() == 0) {
            if (liveResult != null) {
                aggregates = Lists.newArrayList(aggregates);
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = revisedQuery.from();
        if (!aggregates.isEmpty()) {
            nonRolledUpFrom = Iterables.getLast(aggregates).captureTime() + 1;
        }
        List<OverviewAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        if (nonRolledUpFrom <= revisedTo) {
            orderedNonRolledUpAggregates.addAll(
                    aggregateRepository.readOverviewAggregates(agentRollupId,
                            ImmutableAggregateQuery.builder()
                                    .copyFrom(revisedQuery)
                                    .from(nonRolledUpFrom)
                                    .rollupLevel(0)
                                    .build(), CassandraProfile.web));
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
    List<PercentileAggregate> getPercentileAggregates(String agentRollupId, AggregateQuery query,
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
        AggregateQuery revisedQuery = ImmutableAggregateQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<PercentileAggregate> aggregates =
                aggregateRepository.readPercentileAggregates(agentRollupId, revisedQuery, CassandraProfile.web);
        if (revisedQuery.rollupLevel() == 0) {
            if (liveResult != null) {
                aggregates = Lists.newArrayList(aggregates);
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = revisedQuery.from();
        if (!aggregates.isEmpty()) {
            nonRolledUpFrom = Iterables.getLast(aggregates).captureTime() + 1;
        }
        List<PercentileAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        if (nonRolledUpFrom <= revisedTo) {
            orderedNonRolledUpAggregates.addAll(
                    aggregateRepository.readPercentileAggregates(agentRollupId,
                            ImmutableAggregateQuery.builder()
                                    .copyFrom(revisedQuery)
                                    .from(nonRolledUpFrom)
                                    .rollupLevel(0)
                                    .build(), CassandraProfile.web));
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
    List<ThroughputAggregate> getThroughputAggregates(String agentRollupId, AggregateQuery query,
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
        AggregateQuery revisedQuery = ImmutableAggregateQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<ThroughputAggregate> aggregates =
                aggregateRepository.readThroughputAggregates(agentRollupId, revisedQuery, CassandraProfile.web);
        if (revisedQuery.rollupLevel() == 0) {
            if (liveResult != null) {
                aggregates = Lists.newArrayList(aggregates);
                aggregates.addAll(liveResult.get());
            }
            return aggregates;
        }
        long nonRolledUpFrom = revisedQuery.from();
        if (!aggregates.isEmpty()) {
            nonRolledUpFrom = Iterables.getLast(aggregates).captureTime() + 1;
        }
        List<ThroughputAggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        if (nonRolledUpFrom <= revisedTo) {
            orderedNonRolledUpAggregates.addAll(aggregateRepository
                    .readThroughputAggregates(agentRollupId,
                            ImmutableAggregateQuery.builder()
                                    .copyFrom(revisedQuery)
                                    .from(nonRolledUpFrom)
                                    .rollupLevel(0)
                                    .build(), CassandraProfile.web));
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
    QueryCollector getMergedQueries(String agentRollupId, AggregateQuery query)
            throws Exception {
        int maxQueryAggregatesPerTransactionAggregate =
                getMaxQueryAggregatesPerTransactionAggregate(agentRollupId);
        QueryCollector queryCollector =
                new QueryCollector(maxQueryAggregatesPerTransactionAggregate);
        long revisedFrom = query.from();
        long revisedTo =
                liveAggregateRepository.mergeInQueries(agentRollupId, query, queryCollector);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            AggregateQuery revisedQuery = ImmutableAggregateQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeQueriesInto(agentRollupId, revisedQuery, queryCollector, CassandraProfile.web);
            long lastRolledUpTime = queryCollector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return queryCollector;
    }

    // query.from() is non-inclusive
    ServiceCallCollector getMergedServiceCalls(String agentRollupId, AggregateQuery query)
            throws Exception {
        int maxServiceCallAggregatesPerTransactionAggregate =
                getMaxServiceCallAggregatesPerTransactionAggregate(agentRollupId);
        ServiceCallCollector serviceCallCollector =
                new ServiceCallCollector(maxServiceCallAggregatesPerTransactionAggregate);
        long revisedFrom = query.from();
        long revisedTo = liveAggregateRepository.mergeInServiceCalls(agentRollupId, query,
                serviceCallCollector);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            AggregateQuery revisedQuery = ImmutableAggregateQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeServiceCallsInto(agentRollupId, revisedQuery,
                    serviceCallCollector, CassandraProfile.web);
            long lastRolledUpTime = serviceCallCollector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return serviceCallCollector;
    }

    // query.from() is non-inclusive
    ProfileCollector getMergedProfile(String agentRollupId, AggregateQuery query, boolean auxiliary,
            List<String> includes, List<String> excludes, double truncateBranchPercentage)
            throws Exception {
        ProfileCollector profileCollector = getMergedProfile(agentRollupId, query, auxiliary, CassandraProfile.web);
        MutableProfile profile = profileCollector.getProfile();
        if (!includes.isEmpty() || !excludes.isEmpty()) {
            profile.filter(includes, excludes);
        }
        profile.truncateBranches(truncateBranchPercentage);
        return profileCollector;
    }

    @Nullable
    String readFullQueryText(String agentRollupId, String fullQueryTextSha1, CassandraProfile profile) throws Exception {
        // checking live data is not efficient since must perform many sha1 hashes
        // so check repository first
        String fullQueryText =
                aggregateRepository.readFullQueryText(agentRollupId, fullQueryTextSha1, profile);
        if (fullQueryText != null) {
            return fullQueryText;
        }
        return liveAggregateRepository.getFullQueryText(agentRollupId, fullQueryTextSha1);
    }

    boolean hasMainThreadProfile(String agentRollupId, AggregateQuery query) throws Exception {
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            AggregateQuery revisedQuery = ImmutableAggregateQuery.builder()
                    .copyFrom(query)
                    .rollupLevel(rollupLevel)
                    .build();
            if (aggregateRepository.hasMainThreadProfile(agentRollupId, revisedQuery, CassandraProfile.web)) {
                return true;
            }
        }
        return false;
    }

    boolean hasAuxThreadProfile(String agentRollupId, AggregateQuery query) throws Exception {
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            AggregateQuery revisedQuery = ImmutableAggregateQuery.builder()
                    .copyFrom(query)
                    .rollupLevel(rollupLevel)
                    .build();
            if (aggregateRepository.hasAuxThreadProfile(agentRollupId, revisedQuery, CassandraProfile.web)) {
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
                    .mergeMainThreadStats(nonRolledUpOverviewAggregate.mainThreadStats());
            Aggregate.Timer auxThreadRootTimer = nonRolledUpOverviewAggregate.auxThreadRootTimer();
            if (auxThreadRootTimer != null) {
                currMergedAggregate.mergeAuxThreadRootTimer(auxThreadRootTimer);
                // aux thread stats is non-null when aux thread root timer is non-null
                currMergedAggregate.mergeAuxThreadStats(
                        checkNotNull(nonRolledUpOverviewAggregate.auxThreadStats()));
            }
            currMergedAggregate.mergeAsyncTimers(nonRolledUpOverviewAggregate.asyncTimers());
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

    private ProfileCollector getMergedProfile(String agentRollupId, AggregateQuery query,
            boolean auxiliary, CassandraProfile profile) throws Exception {
        ProfileCollector profileCollector = new ProfileCollector();
        long revisedFrom = query.from();
        long revisedTo;
        if (auxiliary) {
            revisedTo = liveAggregateRepository.mergeInAuxThreadProfiles(agentRollupId, query,
                    profileCollector);
        } else {
            revisedTo = liveAggregateRepository.mergeInMainThreadProfiles(agentRollupId, query,
                    profileCollector);
        }
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            AggregateQuery revisedQuery = ImmutableAggregateQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            if (auxiliary) {
                aggregateRepository.mergeAuxThreadProfilesInto(agentRollupId, revisedQuery,
                        profileCollector, profile);
            } else {
                aggregateRepository.mergeMainThreadProfilesInto(agentRollupId, revisedQuery,
                        profileCollector, profile);
            }
            long lastRolledUpTime = profileCollector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return profileCollector;
    }

    private int getMaxQueryAggregatesPerTransactionAggregate(String agentRollupId)
            throws Exception {
        AdvancedConfig advancedConfig;
        try {
            advancedConfig = configRepository.getAdvancedConfig(agentRollupId);
        } catch (AgentConfigNotFoundException e) {
            return ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES;
        }
        if (advancedConfig.hasMaxQueryAggregates()) {
            return advancedConfig.getMaxQueryAggregates().getValue();
        } else {
            return ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES;
        }
    }

    private int getMaxServiceCallAggregatesPerTransactionAggregate(String agentRollupId)
            throws Exception {
        AdvancedConfig advancedConfig;
        try {
            advancedConfig = configRepository.getAdvancedConfig(agentRollupId);
        } catch (AgentConfigNotFoundException e) {
            return ConfigDefaults.ADVANCED_MAX_SERVICE_CALL_AGGREGATES;
        }
        if (advancedConfig.hasMaxServiceCallAggregates()) {
            return advancedConfig.getMaxServiceCallAggregates().getValue();
        } else {
            return ConfigDefaults.ADVANCED_MAX_SERVICE_CALL_AGGREGATES;
        }
    }

    private static class RollupCaptureTimeFn implements Function<Long, Long> {

        private final long fixedIntervalMillis;

        private RollupCaptureTimeFn(long fixedIntervalMillis) {
            this.fixedIntervalMillis = fixedIntervalMillis;
        }

        @Override
        public Long apply(Long captureTime) {
            return CaptureTimes.getRollup(captureTime, fixedIntervalMillis);
        }
    }
}
