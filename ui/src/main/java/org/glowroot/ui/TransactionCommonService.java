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
import java.util.Map;

import javax.annotation.Nullable;

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
    OverallSummary readOverallSummary(String agentRollup, OverallQuery query) throws Exception {
        OverallSummaryCollector collector = new OverallSummaryCollector();
        long revisedFrom = query.from();
        long revisedTo =
                liveAggregateRepository.mergeInOverallSummary(agentRollup, query, collector);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeOverallSummaryInto(agentRollup, revisedQuery, collector);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getOverallSummary();
    }

    // query.from() is non-inclusive
    Result<TransactionSummary> readTransactionSummaries(String agentRollup, OverallQuery query,
            SummarySortOrder sortOrder, int limit) throws Exception {
        TransactionSummaryCollector collector = new TransactionSummaryCollector();
        long revisedFrom = query.from();
        long revisedTo =
                liveAggregateRepository.mergeInTransactionSummaries(agentRollup, query, collector);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            OverallQuery revisedQuery = ImmutableOverallQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeTransactionSummariesInto(agentRollup, revisedQuery, sortOrder,
                    limit, collector);
            long lastRolledUpTime = collector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return collector.getResult(sortOrder, limit);
    }

    // query.from() is INCLUSIVE
    List<OverviewAggregate> getOverviewAggregates(String agentRollup, TransactionQuery query)
            throws Exception {
        LiveResult<OverviewAggregate> liveResult =
                liveAggregateRepository.getOverviewAggregates(agentRollup, query);
        long revisedTo = liveResult == null ? query.to() : liveResult.revisedTo();
        TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<OverviewAggregate> aggregates =
                aggregateRepository.readOverviewAggregates(agentRollup, revisedQuery);
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
                aggregateRepository.readOverviewAggregates(agentRollup,
                        ImmutableTransactionQuery.builder()
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
    List<PercentileAggregate> getPercentileAggregates(String agentRollup, TransactionQuery query)
            throws Exception {
        LiveResult<PercentileAggregate> liveResult =
                liveAggregateRepository.getPercentileAggregates(agentRollup, query);
        long revisedTo = liveResult == null ? query.to() : liveResult.revisedTo();
        TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<PercentileAggregate> aggregates =
                aggregateRepository.readPercentileAggregates(agentRollup, revisedQuery);
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
                aggregateRepository.readPercentileAggregates(agentRollup,
                        ImmutableTransactionQuery.builder()
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
    List<ThroughputAggregate> getThroughputAggregates(String agentRollup, TransactionQuery query)
            throws Exception {
        LiveResult<ThroughputAggregate> liveResult =
                liveAggregateRepository.getThroughputAggregates(agentRollup, query);
        long revisedTo = liveResult == null ? query.to() : liveResult.revisedTo();
        TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                .copyFrom(query)
                .to(revisedTo)
                .build();
        List<ThroughputAggregate> aggregates =
                aggregateRepository.readThroughputAggregates(agentRollup, revisedQuery);
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
        orderedNonRolledUpAggregates.addAll(aggregateRepository
                .readThroughputAggregates(agentRollup,
                        ImmutableTransactionQuery.builder()
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
    Map<String, List<MutableQuery>> getMergedQueries(String agentRollup,
            TransactionQuery query) throws Exception {
        int maxAggregateQueriesPerType = getMaxAggregateQueriesPerType(agentRollup);
        QueryCollector queryCollector = new QueryCollector(maxAggregateQueriesPerType);
        long revisedFrom = query.from();
        long revisedTo = liveAggregateRepository.mergeInQueries(agentRollup, query, queryCollector);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeQueriesInto(agentRollup, revisedQuery, queryCollector);
            long lastRolledUpTime = queryCollector.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        return queryCollector.getSortedQueries();
    }

    @Nullable
    String readFullQueryText(String agentRollup, String fullQueryTextSha1) throws Exception {
        // checking live data is not efficient since must perform many sha1 hashes
        // so check repository first
        String fullQueryText =
                aggregateRepository.readFullQueryText(agentRollup, fullQueryTextSha1);
        if (fullQueryText != null) {
            return fullQueryText;
        }
        return liveAggregateRepository.getFullQueryText(agentRollup, fullQueryTextSha1);
    }

    // query.from() is non-inclusive
    List<Aggregate.ServiceCallsByType> getMergedServiceCalls(String agentRollup,
            TransactionQuery query)
            throws Exception {
        int maxAggregateServiceCallsPerType =
                getMaxAggregateServiceCallsPerType(agentRollup);
        ServiceCallCollector serviceCallCollector =
                new ServiceCallCollector(maxAggregateServiceCallsPerType, 0);
        long revisedFrom = query.from();
        long revisedTo = liveAggregateRepository.mergeInServiceCalls(agentRollup, query,
                serviceCallCollector);
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .from(revisedFrom)
                    .to(revisedTo)
                    .rollupLevel(rollupLevel)
                    .build();
            aggregateRepository.mergeServiceCallsInto(agentRollup, revisedQuery,
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
    MutableProfile getMergedProfile(String agentRollup, TransactionQuery query, boolean auxiliary,
            List<String> includes, List<String> excludes, double truncateBranchPercentage)
            throws Exception {
        MutableProfile profile = getMergedProfile(agentRollup, query, auxiliary);
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

    boolean hasMainThreadProfile(String agentRollup, TransactionQuery query) throws Exception {
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .rollupLevel(rollupLevel)
                    .build();
            if (aggregateRepository.hasMainThreadProfile(agentRollup, revisedQuery)) {
                return true;
            }
        }
        return false;
    }

    boolean hasAuxThreadProfile(String agentRollup, TransactionQuery query) throws Exception {
        for (int rollupLevel = query.rollupLevel(); rollupLevel >= 0; rollupLevel--) {
            TransactionQuery revisedQuery = ImmutableTransactionQuery.builder()
                    .copyFrom(query)
                    .rollupLevel(rollupLevel)
                    .build();
            if (aggregateRepository.hasAuxThreadProfile(agentRollup, revisedQuery)) {
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
                        .add(ImmutableThroughputAggregate.of(currRollupCaptureTime,
                                currTransactionCount));
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

    private MutableProfile getMergedProfile(String agentRollup, TransactionQuery query,
            boolean auxiliary) throws Exception {
        ProfileCollector collector = new ProfileCollector();
        long revisedFrom = query.from();
        long revisedTo;
        if (auxiliary) {
            revisedTo =
                    liveAggregateRepository.mergeInAuxThreadProfiles(agentRollup, query, collector);
        } else {
            revisedTo = liveAggregateRepository.mergeInMainThreadProfiles(agentRollup, query,
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
                aggregateRepository.mergeAuxThreadProfilesInto(agentRollup, revisedQuery,
                        collector);
            } else {
                aggregateRepository.mergeMainThreadProfilesInto(agentRollup, revisedQuery,
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
