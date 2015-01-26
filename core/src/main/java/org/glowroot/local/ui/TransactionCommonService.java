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
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharSource;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateCollector;
import org.glowroot.collector.AggregateIntervalCollector;
import org.glowroot.collector.ImmutableTransactionSummary;
import org.glowroot.collector.LazyHistogram;
import org.glowroot.collector.TransactionSummary;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.TransactionSummarySortOrder;
import org.glowroot.local.store.ImmutableTransactionSummaryQuery;
import org.glowroot.local.store.QueryResult;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionCommonService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AggregateDao aggregateDao;
    private final @Nullable AggregateCollector aggregateCollector;

    TransactionCommonService(AggregateDao aggregateDao,
            @Nullable AggregateCollector aggregateCollector) {
        this.aggregateDao = aggregateDao;
        this.aggregateCollector = aggregateCollector;
    }

    TransactionSummary readOverallTransactionSummary(String transactionType, long from, long to)
            throws SQLException {
        List<AggregateIntervalCollector> intervalCollectors =
                getIntervalCollectorsInRange(from, to);
        long possiblyRevisedTo = to;
        if (!intervalCollectors.isEmpty()) {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping with stored aggregates
            possiblyRevisedTo = getMinEndTime(intervalCollectors) - 1;
        }
        TransactionSummary overallSummary = aggregateDao.readOverallTransactionSummary(
                transactionType, from, possiblyRevisedTo);
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            TransactionSummary liveOverallSummary =
                    intervalCollector.getLiveOverallSummary(transactionType);
            if (liveOverallSummary != null) {
                overallSummary = combineTransactionSummaries(null, overallSummary,
                        liveOverallSummary);
            }
        }
        return overallSummary;
    }

    QueryResult<TransactionSummary> readTransactionSummaries(String transactionType, long from,
            long to, TransactionSummarySortOrder sortOrder, int limit) throws SQLException {
        List<AggregateIntervalCollector> intervalCollectors =
                getIntervalCollectorsInRange(from, to);
        long possiblyRevisedTo = to;
        if (!intervalCollectors.isEmpty()) {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping with stored aggregates
            possiblyRevisedTo = getMinEndTime(intervalCollectors) - 1;
        }
        ImmutableTransactionSummaryQuery query = ImmutableTransactionSummaryQuery.builder()
                .transactionType(transactionType)
                .from(from)
                .to(possiblyRevisedTo)
                .sortOrder(sortOrder)
                .limit(limit)
                .build();
        QueryResult<TransactionSummary> queryResult = aggregateDao.readTransactionSummaries(query);
        if (intervalCollectors.isEmpty()) {
            return queryResult;
        }
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
                    intervalCollector.getLiveTransactionSummaries(transactionType);
            for (TransactionSummary liveTransactionSummary : liveTransactionSummaries) {
                String transactionName = liveTransactionSummary.transactionName();
                // transaction name is only null for overall summary
                checkNotNull(transactionName);
                TransactionSummary transactionSummary = transactionSummaryMap.get(transactionName);
                if (transactionSummary == null) {
                    transactionSummaryMap.put(transactionName, liveTransactionSummary);
                } else {
                    transactionSummaryMap.put(transactionName,
                            combineTransactionSummaries(transactionName, transactionSummary,
                                    liveTransactionSummary));
                }
            }
        }
        transactionSummaries = sortTransactionSummaries(transactionSummaryMap.values(), sortOrder);
        boolean moreAvailable = queryResult.moreAvailable();
        if (transactionSummaries.size() > limit) {
            moreAvailable = true;
            transactionSummaries = transactionSummaries.subList(0, limit);
        }
        return new QueryResult<TransactionSummary>(transactionSummaries, moreAvailable);
    }

    long getProfileSampleCount(String transactionType, @Nullable String transactionName, long from,
            long to) throws SQLException {
        List<AggregateIntervalCollector> intervalCollectors =
                getIntervalCollectorsInRange(from, to);
        long possiblyRevisedTo = to;
        if (!intervalCollectors.isEmpty()) {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping with stored aggregates
            possiblyRevisedTo = getMinEndTime(intervalCollectors) - 1;
        }
        long profileSampleCount;
        if (transactionName == null) {
            profileSampleCount = aggregateDao.readOverallProfileSampleCount(transactionType, from,
                    possiblyRevisedTo);
        } else {
            profileSampleCount = aggregateDao.readTransactionProfileSampleCount(transactionType,
                    transactionName, from, possiblyRevisedTo);
        }
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            profileSampleCount += intervalCollector.getLiveProfileSampleCount(transactionType,
                    transactionName);
        }
        return profileSampleCount;
    }

    List<Aggregate> getAggregates(String transactionType, @Nullable String transactionName,
            long from, long to) throws Exception {
        List<AggregateIntervalCollector> intervalCollectors =
                getIntervalCollectorsInRange(from, to);
        long possiblyRevisedTo = to;
        if (!intervalCollectors.isEmpty()) {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping with stored aggregates
            possiblyRevisedTo = getMinEndTime(intervalCollectors) - 1;
        }
        List<Aggregate> aggregates;
        if (transactionName == null) {
            aggregates = aggregateDao.readOverallAggregates(transactionType, from,
                    possiblyRevisedTo);
        } else {
            aggregates = aggregateDao.readTransactionAggregates(transactionType, transactionName,
                    from, possiblyRevisedTo);
        }
        if (!intervalCollectors.isEmpty()) {
            aggregates = Lists.newArrayList(aggregates);
            for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
                Aggregate liveAggregate =
                        intervalCollector.getLiveAggregate(transactionType, transactionName);
                if (liveAggregate != null) {
                    aggregates.add(liveAggregate);
                }
            }
        }
        return aggregates;
    }

    MetricMergedAggregate getMetricMergedAggregate(List<Aggregate> aggregates) throws Exception {
        long transactionCount = 0;
        AggregateMetric syntheticRootMetric = AggregateMetric.createSyntheticRootMetric();
        for (Aggregate aggregate : aggregates) {
            transactionCount += aggregate.transactionCount();
            AggregateMetric toBeMergedSyntheticRootMetric =
                    mapper.readValue(aggregate.metrics(), AggregateMetric.class);
            mergeMatchedMetric(toBeMergedSyntheticRootMetric, syntheticRootMetric);
        }
        AggregateMetric rootMetric = syntheticRootMetric;
        if (syntheticRootMetric.getNestedMetrics().size() == 1) {
            rootMetric = syntheticRootMetric.getNestedMetrics().get(0);
        }
        return new MetricMergedAggregate(rootMetric, transactionCount);
    }

    HistogramMergedAggregate getHistogramMergedAggregate(List<Aggregate> aggregates)
            throws Exception {
        long transactionCount = 0;
        long totalMicros = 0;
        LazyHistogram histogram = new LazyHistogram();
        for (Aggregate aggregate : aggregates) {
            transactionCount += aggregate.transactionCount();
            totalMicros += aggregate.totalMicros();
            histogram.decodeFromByteBuffer(ByteBuffer.wrap(aggregate.histogram()));
        }
        return new HistogramMergedAggregate(histogram, transactionCount, totalMicros);
    }

    AggregateProfileNode getProfile(String transactionType, @Nullable String transactionName,
            long from, long to, double truncateLeafPercentage) throws Exception {
        List<AggregateIntervalCollector> intervalCollectors =
                getIntervalCollectorsInRange(from, to);
        long possiblyRevisedTo = to;
        if (!intervalCollectors.isEmpty()) {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping with stored aggregates
            possiblyRevisedTo = getMinEndTime(intervalCollectors) - 1;
        }
        List<CharSource> profiles;
        if (transactionName == null) {
            profiles = aggregateDao.readOverallProfiles(transactionType, from, possiblyRevisedTo);
        } else {
            profiles = aggregateDao.readTransactionProfiles(transactionType, transactionName, from,
                    possiblyRevisedTo);
        }
        if (!intervalCollectors.isEmpty()) {
            profiles = Lists.newArrayList(profiles);
            for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
                String profile =
                        intervalCollector.getLiveProfileJson(transactionType, transactionName);
                if (profile != null) {
                    profiles.add(CharSource.wrap(profile));
                }
            }
        }
        return getProfile(profiles, truncateLeafPercentage);
    }

    private List<AggregateIntervalCollector> getIntervalCollectorsInRange(long from, long to) {
        if (aggregateCollector == null) {
            return ImmutableList.of();
        }
        return aggregateCollector.getIntervalCollectorsInRange(from, to);
    }

    private static long getMinEndTime(List<AggregateIntervalCollector> intervalCollectors) {
        long min = Long.MAX_VALUE;
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            min = Math.min(intervalCollector.getEndTime(), min);
        }
        return min;
    }

    private static TransactionSummary combineTransactionSummaries(@Nullable String transactionName,
            TransactionSummary summary1, TransactionSummary summary2) {
        return ImmutableTransactionSummary.builder()
                .transactionName(transactionName)
                .totalMicros(summary1.totalMicros() + summary2.totalMicros())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
    }

    private static List<TransactionSummary> sortTransactionSummaries(
            Iterable<TransactionSummary> transactionSummaries,
            TransactionSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case TOTAL_TIME:
                return TransactionSummary.orderingByTotalTimeDesc.immutableSortedCopy(
                        transactionSummaries);
            case AVERAGE_TIME:
                return TransactionSummary.orderingByAverageTimeDesc.immutableSortedCopy(
                        transactionSummaries);
            case THROUGHPUT:
                return TransactionSummary.orderingByTransactionCountDesc.immutableSortedCopy(
                        transactionSummaries);
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }

    private static void mergeMatchedMetric(AggregateMetric toBeMergedMetric,
            AggregateMetric metric) {
        metric.incrementCount(toBeMergedMetric.getCount());
        metric.incrementTotalMicros(toBeMergedMetric.getTotalMicros());
        for (AggregateMetric toBeMergedNestedMetric : toBeMergedMetric.getNestedMetrics()) {
            // for each to-be-merged child node look for a match
            AggregateMetric foundMatchingChildMetric = null;
            for (AggregateMetric childMetric : metric.getNestedMetrics()) {
                if (toBeMergedNestedMetric.getName().equals(childMetric.getName())) {
                    foundMatchingChildMetric = childMetric;
                    break;
                }
            }
            if (foundMatchingChildMetric == null) {
                metric.getNestedMetrics().add(toBeMergedNestedMetric);
            } else {
                mergeMatchedMetric(toBeMergedNestedMetric, foundMatchingChildMetric);
            }
        }
    }

    private static AggregateProfileNode getProfile(List<CharSource> profiles,
            double truncateLeafPercentage) throws IOException {
        AggregateProfileNode syntheticRootNode = AggregateProfileNode.createSyntheticRootNode();
        for (CharSource profile : profiles) {
            String profileContent = profile.read();
            if (profileContent.equals(AggregateDao.OVERWRITTEN)) {
                continue;
            }
            AggregateProfileNode toBeMergedRootNode = ObjectMappers.readRequiredValue(mapper,
                    profileContent, AggregateProfileNode.class);
            if (toBeMergedRootNode.getStackTraceElement() == null) {
                // to-be-merged root node is already synthetic
                mergeMatchedNode(toBeMergedRootNode, syntheticRootNode);
            } else {
                syntheticRootNode.incrementSampleCount(toBeMergedRootNode.getSampleCount());
                mergeChildNodeIntoParent(toBeMergedRootNode, syntheticRootNode);
            }
        }
        if (truncateLeafPercentage != 0) {
            int minSamples = (int) (syntheticRootNode.getSampleCount() * truncateLeafPercentage);
            truncateLeafs(syntheticRootNode, minSamples);
        }
        if (syntheticRootNode.getChildNodes().size() == 1) {
            // strip off synthetic root node since only one real root node
            return syntheticRootNode.getChildNodes().get(0);
        } else {
            return syntheticRootNode;
        }
    }

    private static void mergeMatchedNode(AggregateProfileNode toBeMergedNode,
            AggregateProfileNode node) {
        node.incrementSampleCount(toBeMergedNode.getSampleCount());
        // the metric names for a given stack element should always match, unless
        // the line numbers aren't available and overloaded methods are matched up, or
        // the stack trace was captured while one of the synthetic $glowroot$metric$ methods was
        // executing in which case one of the metric names may be a subset of the other,
        // in which case, the superset wins:
        List<String> metricNames = toBeMergedNode.getMetricNames();
        if (metricNames.size() > node.getMetricNames().size()) {
            node.setMetricNames(metricNames);
        }
        for (AggregateProfileNode toBeMergedChildNode : toBeMergedNode.getChildNodes()) {
            mergeChildNodeIntoParent(toBeMergedChildNode, node);
        }
    }

    private static void mergeChildNodeIntoParent(AggregateProfileNode toBeMergedChildNode,
            AggregateProfileNode parentNode) {
        // for each to-be-merged child node look for a match
        AggregateProfileNode foundMatchingChildNode = null;
        for (AggregateProfileNode childNode : parentNode.getChildNodes()) {
            if (matches(toBeMergedChildNode, childNode)) {
                foundMatchingChildNode = childNode;
                break;
            }
        }
        if (foundMatchingChildNode == null) {
            parentNode.getChildNodes().add(toBeMergedChildNode);
        } else {
            mergeMatchedNode(toBeMergedChildNode, foundMatchingChildNode);
        }
    }

    private static boolean matches(AggregateProfileNode node1, AggregateProfileNode node2) {
        return Objects.equal(node1.getStackTraceElement(), node2.getStackTraceElement())
                && Objects.equal(node1.getLeafThreadState(), node2.getLeafThreadState());
    }

    private static void truncateLeafs(AggregateProfileNode node, int minSamples) {
        for (Iterator<AggregateProfileNode> i = node.getChildNodes().iterator(); i.hasNext();) {
            AggregateProfileNode childNode = i.next();
            if (childNode.getSampleCount() < minSamples) {
                i.remove();
                node.setEllipsed();
            } else {
                truncateLeafs(childNode, minSamples);
            }
        }
    }

    // could use @Value.Immutable, but it's not technically immutable since it contains
    // non-immutable state (AggregateMetric)
    public static class MetricMergedAggregate {

        private final AggregateMetric rootMetric;
        private final long transactionCount;

        private MetricMergedAggregate(AggregateMetric rootMetric, long transactionCount) {
            this.rootMetric = rootMetric;
            this.transactionCount = transactionCount;
        }

        public AggregateMetric getMetrics() {
            return rootMetric;
        }

        public long getTransactionCount() {
            return transactionCount;
        }
    }

    // could use @Value.Immutable, but it's not technically immutable since it contains
    // non-immutable state (Histogram)
    public static class HistogramMergedAggregate {

        private final long totalMicros;
        private final long transactionCount;
        private final LazyHistogram histogram;

        private HistogramMergedAggregate(LazyHistogram histogram, long transactionCount,
                long totalMicros) {
            this.histogram = histogram;
            this.transactionCount = transactionCount;
            this.totalMicros = totalMicros;
        }

        public long getTransactionCount() {
            return transactionCount;
        }

        public long getTotalMicros() {
            return totalMicros;
        }

        public long getPercentile1() {
            return histogram.getValueAtPercentile(50);
        }

        public long getPercentile2() {
            return histogram.getValueAtPercentile(95);
        }

        public long getPercentile3() {
            return histogram.getValueAtPercentile(99);
        }
    }
}
