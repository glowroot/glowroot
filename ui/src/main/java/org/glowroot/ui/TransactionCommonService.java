/*
 * Copyright 2014-2016 the original author or authors.
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

import java.io.IOException;
import java.util.List;

import com.google.common.collect.Lists;

import org.glowroot.common.live.ImmutableOverallQuery;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.LiveResult;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummarySortOrder;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.util.Clock;
import org.glowroot.storage.config.ConfigDefaults;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.MutableAggregate;
import org.glowroot.storage.repo.Utils;
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
    OverallSummary readOverallSummary(OverallQuery query) throws Exception {
        OverallSummaryCollector collector = new OverallSummaryCollector();
        long revisedFrom = query.from();
        long revisedTo = liveAggregateRepository.mergeInOverallSummary(collector, query);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeInOverallSummary(collector, revisedQuery);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getOverallSummary();
    }

    // query.from() is non-inclusive
    Result<TransactionSummary> readTransactionSummaries(OverallQuery query,
            SummarySortOrder sortOrder, int limit) throws Exception {
        TransactionSummaryCollector collector = new TransactionSummaryCollector();
        long revisedFrom = query.from();
        long revisedTo = liveAggregateRepository.mergeInTransactionSummaries(collector, query);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeInTransactionSummaries(collector, revisedQuery, sortOrder,
                    limit);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getResult(sortOrder, limit);
    }

    // query.from() is INCLUSIVE
    List<OverviewAggregate> getOverviewAggregates(TransactionQuery query) throws Exception {
        LiveResult<OverviewAggregate> liveResult =
                liveAggregateRepository.getOverviewAggregates(query);
        long revisedTo = liveResult == null ? query.to() : liveResult.revisedTo();
        TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<OverviewAggregate> aggregates =
                aggregateRepository.readOverviewAggregates(revisedQuery);
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
        orderedNonRolledUpAggregates.addAll(
                aggregateRepository.readOverviewAggregates(ImmutableTransactionQuery.builder()
                        .copyFrom(revisedQuery)
                        .from(nonRolledUpFrom)
                        .rollupLevel(0)
                        .build()));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(
                rollUpOverviewAggregates(orderedNonRolledUpAggregates, revisedQuery.rollupLevel()));
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
    List<PercentileAggregate> getPercentileAggregates(TransactionQuery query) throws Exception {
        LiveResult<PercentileAggregate> liveResult =
                liveAggregateRepository.getPercentileAggregates(query);
        long revisedTo = liveResult == null ? query.to() : liveResult.revisedTo();
        TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<PercentileAggregate> aggregates =
                aggregateRepository.readPercentileAggregates(revisedQuery);
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
        orderedNonRolledUpAggregates.addAll(
                aggregateRepository.readPercentileAggregates(ImmutableTransactionQuery.builder()
                        .copyFrom(revisedQuery)
                        .from(nonRolledUpFrom)
                        .rollupLevel(0)
                        .build()));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpPercentileAggregates(orderedNonRolledUpAggregates,
                revisedQuery.rollupLevel()));
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
    List<ThroughputAggregate> getThroughputAggregates(TransactionQuery query) throws Exception {
        LiveResult<ThroughputAggregate> liveResult =
                liveAggregateRepository.getThroughputAggregates(query);
        long revisedTo = liveResult == null ? query.to() : liveResult.revisedTo();
        TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<ThroughputAggregate> aggregates =
                aggregateRepository.readThroughputAggregates(revisedQuery);
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
        orderedNonRolledUpAggregates.addAll(
                aggregateRepository.readThroughputAggregates(ImmutableTransactionQuery.builder()
                        .copyFrom(revisedQuery)
                        .from(nonRolledUpFrom)
                        .rollupLevel(0)
                        .build()));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpThroughputAggregates(orderedNonRolledUpAggregates,
                revisedQuery.rollupLevel()));
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
    List<Aggregate.QueriesByType> getMergedQueries(TransactionQuery query) throws Exception {
        int maxAggregateQueriesPerType = getMaxAggregateQueriesPerType(query.agentRollup());
        QueryCollector queryCollector = new QueryCollector(maxAggregateQueriesPerType, 0);
        long revisedFrom = query.from();
        long revisedTo = liveAggregateRepository.mergeInQueries(queryCollector, query);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeInQueries(queryCollector, revisedQuery);
            long lastRolledUpTime = queryCollector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return queryCollector.toProto();
    }

    // query.from() is non-inclusive
    List<Aggregate.ServiceCallsByType> getMergedServiceCalls(TransactionQuery query)
            throws Exception {
        int maxAggregateServiceCallsPerType =
                getMaxAggregateServiceCallsPerType(query.agentRollup());
        ServiceCallCollector serviceCallCollector =
                new ServiceCallCollector(maxAggregateServiceCallsPerType, 0);
        long revisedFrom = query.from();
        long revisedTo = liveAggregateRepository.mergeInServiceCalls(serviceCallCollector, query);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeInServiceCalls(serviceCallCollector, revisedQuery);
            long lastRolledUpTime = serviceCallCollector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return serviceCallCollector.toProto();
    }

    // query.from() is non-inclusive
    MutableProfile getMergedProfile(TransactionQuery query, boolean auxiliary,
            List<String> includes, List<String> excludes, double truncateBranchPercentage)
            throws Exception {
        MutableProfile profile = getMergedProfile(query, auxiliary);
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

    boolean hasAuxThreadProfile(TransactionQuery query) throws Exception {
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .rollupLevel(rollupLevel)
                    .build();
            if (aggregateRepository.hasAuxThreadProfile(revisedQuery)) {
                return true;
            }
        }
        return false;
    }

    private List<OverviewAggregate> rollUpOverviewAggregates(
            List<OverviewAggregate> orderedNonRolledUpOverviewAggregates, int rollupLevel)
            throws Exception {
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        List<OverviewAggregate> rolledUpOverviewAggregates = Lists.newArrayList();
        MutableAggregate currMergedAggregate = new MutableAggregate(0, 0);
        long currRollupCaptureTime = Long.MIN_VALUE;
        long maxCaptureTime = Long.MIN_VALUE;
        for (OverviewAggregate nonRolledUpOverviewAggregate : orderedNonRolledUpOverviewAggregates) {
            maxCaptureTime = nonRolledUpOverviewAggregate.captureTime();
            long rollupCaptureTime =
                    Utils.getRollupCaptureTime(maxCaptureTime, fixedIntervalMillis);
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

    private List<PercentileAggregate> rollUpPercentileAggregates(
            List<PercentileAggregate> orderedNonRolledUpPercentileAggregates, int rollupLevel)
            throws Exception {
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        List<PercentileAggregate> rolledUpPercentileAggregates = Lists.newArrayList();
        MutableAggregate currMergedAggregate = new MutableAggregate(0, 0);
        long currRollupCaptureTime = Long.MIN_VALUE;
        long maxCaptureTime = Long.MIN_VALUE;
        for (PercentileAggregate nonRolledUpPercentileAggregate : orderedNonRolledUpPercentileAggregates) {
            maxCaptureTime = nonRolledUpPercentileAggregate.captureTime();
            long rollupCaptureTime =
                    Utils.getRollupCaptureTime(maxCaptureTime, fixedIntervalMillis);
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

    private List<ThroughputAggregate> rollUpThroughputAggregates(
            List<ThroughputAggregate> orderedNonRolledUpThroughputAggregates, int rollupLevel)
            throws Exception {
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        List<ThroughputAggregate> rolledUpThroughputAggregates = Lists.newArrayList();
        long currTransactionCount = 0;
        long currRollupCaptureTime = Long.MIN_VALUE;
        long maxCaptureTime = Long.MIN_VALUE;
        for (ThroughputAggregate nonRolledUpThroughputAggregate : orderedNonRolledUpThroughputAggregates) {
            maxCaptureTime = nonRolledUpThroughputAggregate.captureTime();
            long rollupCaptureTime =
                    Utils.getRollupCaptureTime(maxCaptureTime, fixedIntervalMillis);
            if (rollupCaptureTime != currRollupCaptureTime && currTransactionCount > 0) {
                rolledUpThroughputAggregates
                        .add(ImmutableThroughputAggregate.of(currRollupCaptureTime, currTransactionCount));
                currTransactionCount = 0;
            }
            currRollupCaptureTime = rollupCaptureTime;
            currTransactionCount += nonRolledUpThroughputAggregate.transactionCount();
        }
        if (currTransactionCount > 0) {
            // roll up final one
            rolledUpThroughputAggregates
                    .add(ImmutableThroughputAggregate.of(maxCaptureTime, currTransactionCount));
        }
        return rolledUpThroughputAggregates;
    }

    private MutableProfile getMergedProfile(TransactionQuery query, boolean auxiliary)
            throws Exception {
        ProfileCollector collector = new ProfileCollector();
        long revisedFrom = query.from();
        long revisedTo;
        if (auxiliary) {
            revisedTo = liveAggregateRepository.mergeInAuxThreadProfiles(collector, query);
        } else {
            revisedTo = liveAggregateRepository.mergeInMainThreadProfiles(collector, query);
        }
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            if (auxiliary) {
                aggregateRepository.mergeInAuxThreadProfiles(collector, revisedQuery);
            } else {
                aggregateRepository.mergeInMainThreadProfiles(collector, revisedQuery);
            }
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getProfile();
    }

    private int getMaxAggregateQueriesPerType(String agentRollup) throws IOException {
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(agentRollup);
        if (advancedConfig != null && advancedConfig.hasMaxAggregateQueriesPerType()) {
            return advancedConfig.getMaxAggregateQueriesPerType().getValue();
        } else {
            return ConfigDefaults.MAX_AGGREGATE_QUERIES_PER_TYPE;
        }
    }

    private int getMaxAggregateServiceCallsPerType(String agentRollup) throws IOException {
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(agentRollup);
        if (advancedConfig != null && advancedConfig.hasMaxAggregateServiceCallsPerType()) {
            return advancedConfig.getMaxAggregateServiceCallsPerType().getValue();
        } else {
            return ConfigDefaults.MAX_AGGREGATE_SERVICE_CALLS_PER_TYPE;
        }
    }
}
