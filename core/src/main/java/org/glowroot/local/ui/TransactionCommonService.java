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
package org.glowroot.local.ui;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateCollector;
import org.glowroot.collector.AggregateIntervalCollector;
import org.glowroot.collector.ProfileAggregate;
import org.glowroot.collector.QueryAggregate;
import org.glowroot.collector.QueryComponent.AggregateQuery;
import org.glowroot.collector.TransactionSummary;
import org.glowroot.common.ScratchBuffer;
import org.glowroot.common.Traverser;
import org.glowroot.config.ConfigService;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.MergedAggregate;
import org.glowroot.local.store.AggregateDao.TransactionSummarySortOrder;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.TransactionSummaryQuery;
import org.glowroot.transaction.model.ProfileNode;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionCommonService {

    private final AggregateDao aggregateDao;
    private final @Nullable AggregateCollector aggregateCollector;
    private final ConfigService configService;

    TransactionCommonService(AggregateDao aggregateDao,
            @Nullable AggregateCollector aggregateCollector, ConfigService configService) {
        this.aggregateDao = aggregateDao;
        this.aggregateCollector = aggregateCollector;
        this.configService = configService;
    }

    // from is non-inclusive
    TransactionSummary readOverallSummary(String transactionType, long from, long to)
            throws SQLException {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        if (orderedIntervalCollectors.isEmpty()) {
            return aggregateDao.readOverallSummary(transactionType, from, to);
        }
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        TransactionSummary overallSummary =
                aggregateDao.readOverallSummary(transactionType, from, revisedTo);
        return mergeInLiveOverallSummaries(transactionType, overallSummary,
                orderedIntervalCollectors);
    }

    // query.from() is non-inclusive
    QueryResult<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws SQLException {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(query.from(), query.to());
        if (orderedIntervalCollectors.isEmpty()) {
            return aggregateDao.readTransactionSummaries(query);
        }
        long revisedTo = getRevisedTo(query.to(), orderedIntervalCollectors);
        TransactionSummaryQuery revisedQuery = query.withTo(revisedTo);
        QueryResult<TransactionSummary> queryResult =
                aggregateDao.readTransactionSummaries(revisedQuery);
        if (orderedIntervalCollectors.isEmpty()) {
            return queryResult;
        }
        return mergeInLiveTransactionSummaries(revisedQuery, queryResult,
                orderedIntervalCollectors);
    }

    // from is non-inclusive
    boolean shouldHaveQueries(String transactionType, @Nullable String transactionName, long from,
            long to) throws SQLException {
        if (transactionName == null) {
            return aggregateDao.shouldHaveOverallQueries(transactionType, from, to);
        } else {
            return aggregateDao.shouldHaveTransactionQueries(transactionType, transactionName, from,
                    to);
        }
    }

    // from is non-inclusive
    boolean shouldHaveProfile(String transactionType, @Nullable String transactionName, long from,
            long to) throws SQLException {
        if (transactionName == null) {
            return aggregateDao.shouldHaveOverallProfile(transactionType, from, to);
        } else {
            return aggregateDao.shouldHaveTransactionProfile(transactionType, transactionName, from,
                    to);
        }
    }

    // from is INCLUSIVE
    List<Aggregate> getAggregates(String transactionType, @Nullable String transactionName,
            long from, long to, long liveCaptureTime) throws Exception {
        int rollupLevel = aggregateDao.getRollupLevelForView(from, to);
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from - 1, to);
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        List<Aggregate> aggregates = getAggregatesFromDao(transactionType, transactionName, from,
                revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            aggregates = Lists.newArrayList(aggregates);
            aggregates.addAll(getLiveAggregates(transactionType, transactionName,
                    orderedIntervalCollectors, liveCaptureTime));
            return aggregates;
        }
        long nonRolledUpFrom = from;
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<Aggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        orderedNonRolledUpAggregates.addAll(getAggregatesFromDao(transactionType, transactionName,
                nonRolledUpFrom, revisedTo, 0));
        orderedNonRolledUpAggregates.addAll(getLiveAggregates(transactionType, transactionName,
                orderedIntervalCollectors, liveCaptureTime));
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUp(transactionType, transactionName, orderedNonRolledUpAggregates,
                liveCaptureTime, rollupLevel));
        return aggregates;
    }

    // from is non-inclusive
    Map<String, List<AggregateQuery>> getQueries(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        List<QueryAggregate> queryAggregates =
                getQueryAggregates(transactionType, transactionName, from, to);
        return AggregateMerging.getOrderedAndTruncatedQueries(queryAggregates,
                configService.getAdvancedConfig().maxAggregateQueriesPerQueryType());
    }

    // from is non-inclusive
    ProfileNode getProfile(String transactionType, @Nullable String transactionName, long from,
            long to, List<String> includes, List<String> excludes, double truncateLeafPercentage)
                    throws Exception {
        List<ProfileAggregate> profileAggregate =
                getProfileAggregates(transactionType, transactionName, from, to);
        ProfileNode syntheticRootNode = AggregateMerging.getMergedProfile(profileAggregate);
        long syntheticRootNodeSampleCount = syntheticRootNode.getSampleCount();
        if (!includes.isEmpty() || !excludes.isEmpty()) {
            filter(syntheticRootNode, includes, excludes);
        }
        if (truncateLeafPercentage != 0) {
            int minSamples =
                    (int) Math.ceil(syntheticRootNode.getSampleCount() * truncateLeafPercentage);
            // don't truncate any root nodes
            truncateLeafs(syntheticRootNode.getChildNodes(), minSamples);
        }
        // retain original sample count for synthetic root node in case of filtered profile
        syntheticRootNode.setSampleCount(syntheticRootNodeSampleCount);
        return syntheticRootNode;
    }

    // from is non-inclusive
    private List<AggregateIntervalCollector> getOrderedIntervalCollectorsInRange(long from,
            long to) {
        if (aggregateCollector == null) {
            return ImmutableList.of();
        }
        return aggregateCollector.getOrderedIntervalCollectorsInRange(from, to);
    }

    // from is INCLUSIVE
    private List<Aggregate> getAggregatesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
                    throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallAggregates(transactionType, from, to, rollupLevel);
        } else {
            return aggregateDao.readTransactionAggregates(transactionType, transactionName, from,
                    to, rollupLevel);
        }
    }

    // this method may return some rolled up query aggregates and some non-rolled up
    // they are all distinct though
    // this is ok since the results of this method are currently just aggregated into single
    // result as opposed to charted over time period
    //
    // from is non-inclusive
    private List<QueryAggregate> getQueryAggregates(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        int rollupLevel = aggregateDao.getRollupLevelForView(from, to);
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        List<QueryAggregate> queryAggregates = getQueryAggregatesFromDao(transactionType,
                transactionName, from, revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            queryAggregates = Lists.newArrayList(queryAggregates);
            queryAggregates.addAll(getLiveQueryAggregates(transactionType, transactionName,
                    orderedIntervalCollectors));
            return queryAggregates;
        }
        long nonRolledUpFrom = from;
        if (!queryAggregates.isEmpty()) {
            long lastRolledUpTime = queryAggregates.get(queryAggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<QueryAggregate> orderedNonRolledUpQueryAggregates = Lists.newArrayList();
        orderedNonRolledUpQueryAggregates.addAll(getQueryAggregatesFromDao(transactionType,
                transactionName, nonRolledUpFrom, revisedTo, 0));
        orderedNonRolledUpQueryAggregates.addAll(getLiveQueryAggregates(transactionType,
                transactionName, orderedIntervalCollectors));
        queryAggregates = Lists.newArrayList(queryAggregates);
        queryAggregates.addAll(orderedNonRolledUpQueryAggregates);
        return queryAggregates;
    }

    // from is non-inclusive
    private List<QueryAggregate> getQueryAggregatesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
                    throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallQueryAggregates(transactionType, from, to, rollupLevel);
        } else {
            return aggregateDao.readTransactionQueryAggregates(transactionType, transactionName,
                    from, to, rollupLevel);
        }
    }

    // this method may return some rolled up profile aggregates and some non-rolled up
    // they are all distinct though
    // this is ok since the results of this method are currently just aggregated into single
    // result as opposed to charted over time period
    //
    // from is non-inclusive
    private List<ProfileAggregate> getProfileAggregates(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        int rollupLevel = aggregateDao.getRollupLevelForView(from, to);
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        List<ProfileAggregate> profileAggregates = getProfileAggregatesFromDao(transactionType,
                transactionName, from, revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            profileAggregates = Lists.newArrayList(profileAggregates);
            profileAggregates.addAll(getLiveProfileAggregates(transactionType, transactionName,
                    orderedIntervalCollectors));
            return profileAggregates;
        }
        long nonRolledUpFrom = from;
        if (!profileAggregates.isEmpty()) {
            long lastRolledUpTime =
                    profileAggregates.get(profileAggregates.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<ProfileAggregate> orderedNonRolledUpProfileAggregates = Lists.newArrayList();
        orderedNonRolledUpProfileAggregates.addAll(getProfileAggregatesFromDao(transactionType,
                transactionName, nonRolledUpFrom, revisedTo, 0));
        orderedNonRolledUpProfileAggregates.addAll(getLiveProfileAggregates(transactionType,
                transactionName, orderedIntervalCollectors));
        profileAggregates = Lists.newArrayList(profileAggregates);
        profileAggregates.addAll(orderedNonRolledUpProfileAggregates);
        return profileAggregates;
    }

    // from is non-inclusive
    private List<ProfileAggregate> getProfileAggregatesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
                    throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallProfileAggregates(transactionType, from, to,
                    rollupLevel);
        } else {
            return aggregateDao.readTransactionProfileAggregates(transactionType, transactionName,
                    from, to, rollupLevel);
        }
    }

    private List<Aggregate> rollUp(String transactionType, @Nullable String transactionName,
            List<Aggregate> orderedNonRolledUpAggregates, long liveCaptureTime, int rollupLevel)
                    throws Exception {
        long fixedIntervalMillis =
                configService.getRollupConfigs().get(rollupLevel).intervalMillis();
        List<Aggregate> rolledUpAggregates = Lists.newArrayList();
        ScratchBuffer scratchBuffer = new ScratchBuffer();
        MergedAggregate currMergedAggregate = null;
        long currRollupTime = Long.MIN_VALUE;
        for (Aggregate nonRolledUpAggregate : orderedNonRolledUpAggregates) {
            long rollupTime = AggregateDao.getNextRollupTime(
                    nonRolledUpAggregate.captureTime(), fixedIntervalMillis);
            if (rollupTime != currRollupTime && currMergedAggregate != null) {
                rolledUpAggregates.add(currMergedAggregate.toAggregate(scratchBuffer));
                currMergedAggregate = new MergedAggregate(Math.min(rollupTime, liveCaptureTime),
                        transactionType, transactionName,
                        configService.getAdvancedConfig().maxAggregateQueriesPerQueryType());
            }
            if (currMergedAggregate == null) {
                currMergedAggregate = new MergedAggregate(Math.min(rollupTime, liveCaptureTime),
                        transactionType, transactionName,
                        configService.getAdvancedConfig().maxAggregateQueriesPerQueryType());
            }
            currRollupTime = rollupTime;
            currMergedAggregate.addTotalMicros(nonRolledUpAggregate.totalMicros());
            currMergedAggregate.addErrorCount(nonRolledUpAggregate.errorCount());
            currMergedAggregate.addTransactionCount(nonRolledUpAggregate.transactionCount());
            currMergedAggregate.addTotalCpuMicros(nonRolledUpAggregate.totalCpuMicros());
            currMergedAggregate.addTotalBlockedMicros(nonRolledUpAggregate.totalBlockedMicros());
            currMergedAggregate.addTotalWaitedMicros(nonRolledUpAggregate.totalWaitedMicros());
            currMergedAggregate
                    .addTotalAllocatedKBytes(nonRolledUpAggregate.totalAllocatedKBytes());
            currMergedAggregate.addTimers(nonRolledUpAggregate.timers());
            currMergedAggregate.addHistogram(nonRolledUpAggregate.histogram());
        }
        if (currMergedAggregate != null) {
            // roll up final one
            rolledUpAggregates.add(currMergedAggregate.toAggregate(scratchBuffer));
        }
        return rolledUpAggregates;
    }

    private static long getRevisedTo(long to,
            List<AggregateIntervalCollector> orderedIntervalCollectors) {
        if (orderedIntervalCollectors.isEmpty()) {
            return to;
        } else {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping between live and stored aggregates
            return orderedIntervalCollectors.get(0).getEndTime() - 1;
        }
    }

    private TransactionSummary mergeInLiveOverallSummaries(String transactionType,
            TransactionSummary overallSummary,
            List<AggregateIntervalCollector> intervalCollectors) {
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            TransactionSummary liveOverallSummary =
                    intervalCollector.getLiveOverallSummary(transactionType);
            if (liveOverallSummary != null) {
                overallSummary =
                        combineTransactionSummaries(null, overallSummary, liveOverallSummary);
            }
        }
        return overallSummary;
    }

    private static QueryResult<TransactionSummary> mergeInLiveTransactionSummaries(
            TransactionSummaryQuery query, QueryResult<TransactionSummary> queryResult,
            List<AggregateIntervalCollector> intervalCollectors) {
        List<TransactionSummary> transactionSummaries = queryResult.records();
        Map<String, TransactionSummary> transactionSummaryMap = Maps.newHashMap();
        for (TransactionSummary transactionSummary : transactionSummaries) {
            String transactionName = transactionSummary.transactionName();
            // transaction name is only null for overall summary
            checkNotNull(transactionName);
            transactionSummaryMap.put(transactionName, transactionSummary);
        }
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            List<TransactionSummary> liveTransactionSummaries =
                    intervalCollector.getLiveTransactionSummaries(query.transactionType());
            for (TransactionSummary liveTransactionSummary : liveTransactionSummaries) {
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
        transactionSummaries =
                sortTransactionSummaries(transactionSummaryMap.values(), query.sortOrder());
        boolean moreAvailable = queryResult.moreAvailable();
        if (transactionSummaries.size() > query.limit()) {
            moreAvailable = true;
            transactionSummaries = transactionSummaries.subList(0, query.limit());
        }
        return new QueryResult<TransactionSummary>(transactionSummaries, moreAvailable);
    }

    private static List<Aggregate> getLiveAggregates(String transactionType,
            @Nullable String transactionName, List<AggregateIntervalCollector> intervalCollectors,
            long liveCaptureTime) throws IOException {
        List<Aggregate> aggregates = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            Aggregate liveAggregate = intervalCollector.getLiveAggregate(transactionType,
                    transactionName, liveCaptureTime);
            if (liveAggregate != null) {
                aggregates.add(liveAggregate);
            }
        }
        return aggregates;
    }

    private static List<QueryAggregate> getLiveQueryAggregates(String transactionType,
            @Nullable String transactionName, List<AggregateIntervalCollector> intervalCollectors)
                    throws IOException {
        List<QueryAggregate> queryAggregates = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            QueryAggregate liveQueryAggregate =
                    intervalCollector.getLiveQueryAggregate(transactionType, transactionName);
            if (liveQueryAggregate != null) {
                queryAggregates.add(liveQueryAggregate);
            }
        }
        return queryAggregates;
    }

    private static List<ProfileAggregate> getLiveProfileAggregates(String transactionType,
            @Nullable String transactionName, List<AggregateIntervalCollector> intervalCollectors)
                    throws IOException {
        List<ProfileAggregate> profileAggregates = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            ProfileAggregate liveProfileAggregate =
                    intervalCollector.getLiveProfileAggregate(transactionType, transactionName);
            if (liveProfileAggregate != null) {
                profileAggregates.add(liveProfileAggregate);
            }
        }
        return profileAggregates;
    }

    private static TransactionSummary combineTransactionSummaries(@Nullable String transactionName,
            TransactionSummary summary1, TransactionSummary summary2) {
        return TransactionSummary.builder()
                .transactionName(transactionName)
                .totalMicros(summary1.totalMicros() + summary2.totalMicros())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
    }

    // using non-recursive algorithm to avoid stack overflow error on deep profiles
    private static void filter(ProfileNode syntheticRootNode, List<String> includes,
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
    private static void truncateLeafs(Iterable<ProfileNode> rootNodes, int minSamples) {
        Deque<ProfileNode> toBeVisited = new ArrayDeque<ProfileNode>();
        for (ProfileNode rootNode : rootNodes) {
            toBeVisited.add(rootNode);
        }
        ProfileNode node;
        while ((node = toBeVisited.poll()) != null) {
            for (Iterator<ProfileNode> i = node.getChildNodes().iterator(); i.hasNext();) {
                ProfileNode childNode = i.next();
                if (childNode.getSampleCount() < minSamples) {
                    i.remove();
                    // TODO capture sampleCount per timerName of non-ellipsed structure
                    // and use this in UI dropdown filter of timer names
                    // (currently sampleCount per timerName of ellipsed structure is used)
                    node.incrementEllipsedSampleCount((int) childNode.getSampleCount());
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
                return TransactionSummary.orderingByTotalTimeDesc
                        .immutableSortedCopy(transactionSummaries);
            case AVERAGE_TIME:
                return TransactionSummary.orderingByAverageTimeDesc
                        .immutableSortedCopy(transactionSummaries);
            case THROUGHPUT:
                return TransactionSummary.orderingByTransactionCountDesc
                        .immutableSortedCopy(transactionSummaries);
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }

    private static class ProfileFilterer extends Traverser<ProfileNode, RuntimeException> {

        private final String filterTextUpper;
        private final boolean exclusion;

        private ProfileFilterer(ProfileNode rootNode, String filterText, boolean exclusion) {
            super(rootNode);
            this.filterTextUpper = filterText.toUpperCase(Locale.ENGLISH);
            this.exclusion = exclusion;
        }

        @Override
        public List<ProfileNode> visit(ProfileNode node) {
            if (isMatch(node)) {
                node.setMatched();
                // no need to visit children
                return ImmutableList.of();
            }
            return ImmutableList.copyOf(node.getChildNodes());
        }

        @Override
        public void revisitAfterChildren(ProfileNode node) {
            if (node.isMatched()) {
                // if exclusion then node will be removed by parent
                // if not exclusion then keep node and all children
                return;
            }
            if (node.isChildNodesEmpty()) {
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
            for (Iterator<ProfileNode> i = node.iterator(); i.hasNext();) {
                ProfileNode childNode = i.next();
                if (exclusion == !childNode.isMatched()) {
                    filteredSampleCount += childNode.getSampleCount();
                } else {
                    i.remove();
                }
            }
            node.setSampleCount(filteredSampleCount);
        }

        private boolean isMatch(ProfileNode node) {
            String stackTraceElementUpper =
                    node.getStackTraceElementStr().toUpperCase(Locale.ENGLISH);
            if (stackTraceElementUpper.contains(filterTextUpper)) {
                return true;
            }
            String leafThreadState = node.getLeafThreadState();
            if (leafThreadState != null) {
                String leafThreadStateUpper = leafThreadState.toUpperCase(Locale.ENGLISH);
                if (leafThreadStateUpper.contains(filterTextUpper)) {
                    return true;
                }
            }
            return false;
        }

        private boolean removeNode(ProfileNode node) {
            if (node.isSyntheticRootNode()) {
                return false;
            }
            if (exclusion) {
                return hasOnlyMatchedChildren(node);
            } else {
                return hasNoMatchedChildren(node);
            }
        }

        private boolean hasOnlyMatchedChildren(ProfileNode node) {
            for (ProfileNode childNode : node) {
                if (!childNode.isMatched()) {
                    return false;
                }
            }
            return true;
        }

        private boolean hasNoMatchedChildren(ProfileNode node) {
            for (ProfileNode childNode : node) {
                if (childNode.isMatched()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class ProfileResetMatches extends Traverser<ProfileNode, RuntimeException> {

        private ProfileResetMatches(ProfileNode rootNode) {
            super(rootNode);
        }

        @Override
        public List<ProfileNode> visit(ProfileNode node) throws RuntimeException {
            node.resetMatched();
            return ImmutableList.copyOf(node.getChildNodes());
        }

        @Override
        public void revisitAfterChildren(ProfileNode node) throws RuntimeException {}
    }
}
