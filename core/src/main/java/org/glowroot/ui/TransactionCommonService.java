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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;

import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.LiveResult;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.AggregateRepository.OverallSummary;
import org.glowroot.common.repo.AggregateRepository.OverviewAggregate;
import org.glowroot.common.repo.AggregateRepository.PercentileAggregate;
import org.glowroot.common.repo.AggregateRepository.TransactionSummary;
import org.glowroot.common.repo.AggregateRepository.TransactionSummaryQuery;
import org.glowroot.common.repo.AggregateRepository.TransactionSummarySortOrder;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ImmutableOverallSummary;
import org.glowroot.common.repo.ImmutableTransactionSummary;
import org.glowroot.common.repo.ImmutableTransactionSummaryQuery;
import org.glowroot.common.repo.MutableAggregate;
import org.glowroot.common.repo.MutableProfileNode;
import org.glowroot.common.repo.MutableQuery;
import org.glowroot.common.repo.ProfileCollector;
import org.glowroot.common.repo.QueryCollector;
import org.glowroot.common.repo.Result;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Traverser;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionCommonService {

    private static final Ordering<TransactionSummary> orderingByTotalTimeDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(@Nullable TransactionSummary left,
                        @Nullable TransactionSummary right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    return Doubles.compare(right.totalNanos(), left.totalNanos());
                }
            };

    private static final Ordering<TransactionSummary> orderingByAverageTimeDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(@Nullable TransactionSummary left,
                        @Nullable TransactionSummary right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    return Doubles.compare(right.totalNanos() / right.transactionCount(),
                            left.totalNanos() / left.transactionCount());
                }
            };

    private static final Ordering<TransactionSummary> orderingByTransactionCountDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(@Nullable TransactionSummary left,
                        @Nullable TransactionSummary right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    return Longs.compare(right.transactionCount(), left.transactionCount());
                }
            };

    private final AggregateRepository aggregateRepository;
    private final LiveAggregateRepository liveAggregateRepository;
    private final ConfigRepository configRepository;

    TransactionCommonService(AggregateRepository aggregateRepository,
            LiveAggregateRepository liveAggregateRepository, ConfigRepository configRepository) {
        this.aggregateRepository = aggregateRepository;
        this.liveAggregateRepository = liveAggregateRepository;
        this.configRepository = configRepository;
    }

    // from is non-inclusive
    OverallSummary readOverallSummary(String transactionType, long from, long to) throws Exception {
        LiveResult<OverallSummary> liveResult =
                liveAggregateRepository.getLiveOverallSummary(transactionType, from, to);
        if (liveResult == null) {
            return aggregateRepository.readOverallSummary(transactionType, from, to);
        }
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult.initialCaptureTime() - 1;
        OverallSummary overallSummary =
                aggregateRepository.readOverallSummary(transactionType, from, revisedTo);
        for (OverallSummary liveOverallSummary : liveResult.get()) {
            overallSummary = combineOverallSummaries(overallSummary, liveOverallSummary);
        }
        return overallSummary;
    }

    // query.from() is non-inclusive
    Result<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws Exception {
        LiveResult<List<TransactionSummary>> liveResult = liveAggregateRepository
                .getLiveTransactionSummaries(query.transactionType(), query.from(), query.to());
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
    boolean shouldHaveQueries(String transactionType, @Nullable String transactionName, long from,
            long to) throws Exception {
        if (transactionName == null) {
            return aggregateRepository.shouldHaveOverallQueries(transactionType, from, to);
        } else {
            return aggregateRepository.shouldHaveTransactionQueries(transactionType,
                    transactionName, from, to);
        }
    }

    // from is non-inclusive
    boolean shouldHaveProfile(String transactionType, @Nullable String transactionName, long from,
            long to) throws Exception {
        if (transactionName == null) {
            return aggregateRepository.shouldHaveOverallProfile(transactionType, from, to);
        } else {
            return aggregateRepository.shouldHaveTransactionProfile(transactionType,
                    transactionName, from, to);
        }
    }

    // from is INCLUSIVE
    List<OverviewAggregate> getOverviewAggregates(String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception {
        int rollupLevel = aggregateRepository.getRollupLevelForView(from, to);
        LiveResult<OverviewAggregate> liveResult =
                liveAggregateRepository.getLiveOverviewAggregates(transactionType, transactionName,
                        from - 1, to, liveCaptureTime);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        List<OverviewAggregate> aggregates = getOverviewAggregatesFromDao(transactionType,
                transactionName, from, revisedTo, rollupLevel);
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
        orderedNonRolledUpAggregates.addAll(getOverviewAggregatesFromDao(transactionType,
                transactionName, nonRolledUpFrom, revisedTo, 0));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpOverviewAggregates(orderedNonRolledUpAggregates, liveCaptureTime,
                rollupLevel));
        return aggregates;
    }

    // from is INCLUSIVE
    List<PercentileAggregate> getPercentileAggregates(String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception {
        int rollupLevel = aggregateRepository.getRollupLevelForView(from, to);
        LiveResult<PercentileAggregate> liveResult =
                liveAggregateRepository.getLivePercentileAggregates(transactionType,
                        transactionName, from - 1, to, liveCaptureTime);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        List<PercentileAggregate> aggregates = getPercentileAggregatesFromDao(transactionType,
                transactionName, from, revisedTo, rollupLevel);
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
        orderedNonRolledUpAggregates.addAll(getPercentileAggregatesFromDao(transactionType,
                transactionName, nonRolledUpFrom, revisedTo, 0));
        if (liveResult != null) {
            orderedNonRolledUpAggregates.addAll(liveResult.get());
        }
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUpPercentileAggregates(orderedNonRolledUpAggregates, liveCaptureTime,
                rollupLevel));
        return aggregates;
    }

    // from is non-inclusive
    MutableProfileNode getMergedProfile(String transactionType, @Nullable String transactionName,
            long from, long to, List<String> includes, List<String> excludes,
            double truncateLeafPercentage) throws Exception {
        MutableProfileNode syntheticRootNode =
                getMergedProfile(transactionType, transactionName, from, to);
        long syntheticRootNodeSampleCount = syntheticRootNode.sampleCount();
        if (!includes.isEmpty() || !excludes.isEmpty()) {
            filter(syntheticRootNode, includes, excludes);
        }
        if (truncateLeafPercentage != 0) {
            int minSamples =
                    (int) Math.ceil(syntheticRootNode.sampleCount() * truncateLeafPercentage);
            // don't truncate any root nodes
            truncateLeafs(syntheticRootNode.childNodes(), minSamples);
        }
        // retain original sample count for synthetic root node in case of filtered profile
        syntheticRootNode.setSampleCount(syntheticRootNodeSampleCount);
        return syntheticRootNode;
    }

    // from is non-inclusive
    Map<String, List<MutableQuery>> getMergedQueries(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        return getMergedQueries(transactionType, transactionName, from, to,
                configRepository.getAdvancedConfig().maxAggregateQueriesPerQueryType());
    }

    // from is INCLUSIVE
    private List<OverviewAggregate> getOverviewAggregatesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
                    throws Exception {
        if (transactionName == null) {
            return aggregateRepository.readOverallOverviewAggregates(transactionType, from, to,
                    rollupLevel);
        } else {
            return aggregateRepository.readTransactionOverviewAggregates(transactionType,
                    transactionName, from, to, rollupLevel);
        }
    }

    // from is INCLUSIVE
    private List<PercentileAggregate> getPercentileAggregatesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
                    throws Exception {
        if (transactionName == null) {
            return aggregateRepository.readOverallPercentileAggregates(transactionType, from, to,
                    rollupLevel);
        } else {
            return aggregateRepository.readTransactionPercentileAggregates(transactionType,
                    transactionName, from, to, rollupLevel);
        }
    }

    // this method may return some rolled up profile aggregates and some non-rolled up
    // they are all distinct though
    // this is ok since the results of this method are currently just aggregated into single
    // result as opposed to charted over time period
    //
    // from is non-inclusive
    private MutableProfileNode getMergedProfile(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        int initialRollupLevel = aggregateRepository.getRollupLevelForView(from, to);
        LiveResult<MutableProfileNode> liveResult =
                liveAggregateRepository.getLiveProfile(transactionType, transactionName, from, to);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        long revisedFrom = from;
        ProfileCollector mergedProfile = new ProfileCollector();
        for (int rollupLevel = initialRollupLevel; rollupLevel >= 0; rollupLevel--) {
            mergeInProfileFromDao(mergedProfile, transactionType, transactionName, revisedFrom,
                    revisedTo, rollupLevel);
            long lastRolledUpTime = mergedProfile.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        if (liveResult != null) {
            for (MutableProfileNode syntheticRootNode : liveResult.get()) {
                mergedProfile.mergeSyntheticRootNode(syntheticRootNode);
            }
        }
        return mergedProfile.getSyntheticRootNode();
    }

    // this method may return some rolled up query aggregates and some non-rolled up
    // they are all distinct though
    // this is ok since the results of this method are currently just aggregated into single
    // result as opposed to charted over time period
    //
    // from is non-inclusive
    private Map<String, List<MutableQuery>> getMergedQueries(String transactionType,
            @Nullable String transactionName, long from, long to,
            int maxAggregateQueriesPerQueryType) throws Exception {
        int initialRollupLevel = aggregateRepository.getRollupLevelForView(from, to);
        LiveResult<Map<String, List<MutableQuery>>> liveResult =
                liveAggregateRepository.getLiveQueries(transactionType, transactionName, from, to);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        long revisedFrom = from;
        QueryCollector mergedQueries = new QueryCollector(maxAggregateQueriesPerQueryType, 0);
        for (int rollupLevel = initialRollupLevel; rollupLevel >= 0; rollupLevel--) {
            mergeInQueriesFromDao(mergedQueries, transactionType, transactionName, from, revisedTo,
                    rollupLevel);
            long lastRolledUpTime = mergedQueries.getLastCaptureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
            if (revisedFrom > revisedTo) {
                break;
            }
        }
        if (liveResult != null) {
            for (Map<String, List<MutableQuery>> queries : liveResult.get()) {
                mergedQueries.mergeQueries(queries);
            }
        }
        return mergedQueries.getOrderedAndTruncatedQueries();
    }

    // from is non-inclusive
    private void mergeInProfileFromDao(ProfileCollector mergedProfile, String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
                    throws Exception {
        if (transactionName == null) {
            aggregateRepository.mergeInOverallProfile(mergedProfile, transactionType, from, to,
                    rollupLevel);
        } else {
            aggregateRepository.mergeInTransactionProfile(mergedProfile, transactionType,
                    transactionName, from, to, rollupLevel);
        }
    }

    // from is non-inclusive
    private void mergeInQueriesFromDao(QueryCollector mergedQueries, String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
                    throws Exception {
        if (transactionName == null) {
            aggregateRepository.mergeInOverallQueries(mergedQueries, transactionType, from, to,
                    rollupLevel);
        } else {
            aggregateRepository.mergeInTransactionQueries(mergedQueries, transactionType,
                    transactionName, from, to, rollupLevel);
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
                rolledUpOverviewAggregates.add(currMergedAggregate.toOverviewAggregate());
                currMergedAggregate = new MutableAggregate(Math.min(rollupTime, liveCaptureTime),
                        configRepository.getAdvancedConfig().maxAggregateQueriesPerQueryType());
            }
            if (currMergedAggregate == null) {
                currMergedAggregate = new MutableAggregate(Math.min(rollupTime, liveCaptureTime),
                        configRepository.getAdvancedConfig().maxAggregateQueriesPerQueryType());
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
            currMergedAggregate.addTimers(nonRolledUpOverviewAggregate.syntheticRootTimer());
        }
        if (currMergedAggregate != null) {
            // roll up final one
            rolledUpOverviewAggregates.add(currMergedAggregate.toOverviewAggregate());
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
                rolledUpPercentileAggregates.add(currMergedAggregate.toPercentileAggregate());
                currMergedAggregate = new MutableAggregate(Math.min(rollupTime, liveCaptureTime),
                        configRepository.getAdvancedConfig().maxAggregateQueriesPerQueryType());
            }
            if (currMergedAggregate == null) {
                currMergedAggregate = new MutableAggregate(Math.min(rollupTime, liveCaptureTime),
                        configRepository.getAdvancedConfig().maxAggregateQueriesPerQueryType());
            }
            currRollupTime = rollupTime;
            currMergedAggregate.addTotalNanos(nonRolledUpPercentileAggregate.totalNanos());
            currMergedAggregate
                    .addTransactionCount(nonRolledUpPercentileAggregate.transactionCount());
            currMergedAggregate.addHistogram(nonRolledUpPercentileAggregate.histogram());
        }
        if (currMergedAggregate != null) {
            // roll up final one
            rolledUpPercentileAggregates.add(currMergedAggregate.toPercentileAggregate());
        }
        return rolledUpPercentileAggregates;
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

    // using non-recursive algorithm to avoid stack overflow error on deep profiles
    private static void filter(MutableProfileNode syntheticRootNode, List<String> includes,
            List<String> excludes) {
        for (String include : includes) {
            if (syntheticRootNode.isMatched()) {
                new ProfileResetMatches(syntheticRootNode).traverse();
            }
            new ProfileFilterer(syntheticRootNode, include, false).traverse();
        }
        for (String exclude : excludes) {
            // reset is only needed prior to first exclusion, since exclusions won't leave behind
            // any matched nodes
            if (syntheticRootNode.isMatched()) {
                new ProfileResetMatches(syntheticRootNode).traverse();
            }
            new ProfileFilterer(syntheticRootNode, exclude, true).traverse();
        }
    }

    // using non-recursive algorithm to avoid stack overflow error on deep profiles
    private static void truncateLeafs(Iterable<MutableProfileNode> rootNodes, int minSamples) {
        Deque<MutableProfileNode> toBeVisited = new ArrayDeque<MutableProfileNode>();
        for (MutableProfileNode rootNode : rootNodes) {
            toBeVisited.add(rootNode);
        }
        MutableProfileNode node;
        while ((node = toBeVisited.poll()) != null) {
            for (Iterator<MutableProfileNode> i = node.childNodes().iterator(); i.hasNext();) {
                MutableProfileNode childNode = i.next();
                if (childNode.sampleCount() < minSamples) {
                    i.remove();
                    // TODO capture sampleCount per timerName of non-ellipsed structure
                    // and use this in UI dropdown filter of timer names
                    // (currently sampleCount per timerName of ellipsed structure is used)
                    node.incrementEllipsedSampleCount((int) childNode.sampleCount());
                } else {
                    toBeVisited.add(childNode);
                }
            }
        }
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

    private static class ProfileFilterer extends Traverser<MutableProfileNode, RuntimeException> {

        private final String filterTextUpper;
        private final boolean exclusion;

        private ProfileFilterer(MutableProfileNode rootNode, String filterText, boolean exclusion) {
            super(rootNode);
            this.filterTextUpper = filterText.toUpperCase(Locale.ENGLISH);
            this.exclusion = exclusion;
        }

        @Override
        public Iterator<? extends MutableProfileNode> visit(MutableProfileNode node) {
            if (isMatch(node)) {
                node.setMatched();
                // no need to visit children
                return ImmutableSet.<MutableProfileNode>of().iterator();
            }
            return node.childNodes().iterator();
        }

        @Override
        public void revisitAfterChildren(MutableProfileNode node) {
            if (node.isMatched()) {
                // if exclusion then node will be removed by parent
                // if not exclusion then keep node and all children
                return;
            }
            if (node.isEmpty()) {
                return;
            }
            if (removeNode(node)) {
                // node will be removed by parent
                if (exclusion) {
                    node.setMatched();
                }
                return;
            }
            if (!exclusion) {
                node.setMatched();
            }
            // node is a partial match, need to filter it out
            long filteredSampleCount = 0;
            for (Iterator<MutableProfileNode> i = node.iterator(); i.hasNext();) {
                MutableProfileNode childNode = i.next();
                if (exclusion == !childNode.isMatched()) {
                    filteredSampleCount += childNode.sampleCount();
                } else {
                    i.remove();
                }
            }
            node.setSampleCount(filteredSampleCount);
        }

        private boolean isMatch(MutableProfileNode node) {
            StackTraceElement stackTraceElement = node.stackTraceElement();
            if (stackTraceElement == null) {
                return false;
            }
            String stackTraceElementUpper =
                    stackTraceElement.toString().toUpperCase(Locale.ENGLISH);
            if (stackTraceElementUpper.contains(filterTextUpper)) {
                return true;
            }
            String leafThreadState = node.leafThreadState();
            if (leafThreadState != null) {
                String leafThreadStateUpper = leafThreadState.toUpperCase(Locale.ENGLISH);
                if (leafThreadStateUpper.contains(filterTextUpper)) {
                    return true;
                }
            }
            return false;
        }

        private boolean removeNode(MutableProfileNode node) {
            if (node.isSyntheticRootNode()) {
                return false;
            }
            if (exclusion) {
                return hasOnlyMatchedChildren(node);
            } else {
                return hasNoMatchedChildren(node);
            }
        }

        private boolean hasOnlyMatchedChildren(MutableProfileNode node) {
            for (MutableProfileNode childNode : node) {
                if (!childNode.isMatched()) {
                    return false;
                }
            }
            return true;
        }

        private boolean hasNoMatchedChildren(MutableProfileNode node) {
            for (MutableProfileNode childNode : node) {
                if (childNode.isMatched()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class ProfileResetMatches
            extends Traverser<MutableProfileNode, RuntimeException> {

        private ProfileResetMatches(MutableProfileNode rootNode) {
            super(rootNode);
        }

        @Override
        public Iterator<? extends MutableProfileNode> visit(MutableProfileNode node)
                throws RuntimeException {
            node.resetMatched();
            return node.childNodes().iterator();
        }

        @Override
        public void revisitAfterChildren(MutableProfileNode node) throws RuntimeException {}
    }
}
