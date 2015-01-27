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
package org.glowroot.local.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharSource;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.LazyHistogram;

public class AggregateMerging {

    private static final ObjectMapper mapper = new ObjectMapper();

    private AggregateMerging() {}

    public static MetricMergedAggregate getMetricMergedAggregate(List<Aggregate> aggregates)
            throws Exception {
        long transactionCount = 0;
        AggregateMetric syntheticRootMetric = AggregateMetric.createSyntheticRootMetric();
        for (Aggregate aggregate : aggregates) {
            transactionCount += aggregate.transactionCount();
            AggregateMetric toBeMergedSyntheticRootMetric =
                    mapper.readValue(aggregate.metrics(), AggregateMetric.class);
            syntheticRootMetric.mergeMatchedMetric(toBeMergedSyntheticRootMetric);
        }
        if (syntheticRootMetric.getNestedMetrics().size() == 1) {
            // strip off synthetic root node
            return new MetricMergedAggregate(syntheticRootMetric.getNestedMetrics().get(0),
                    transactionCount);
        } else {
            return new MetricMergedAggregate(syntheticRootMetric, transactionCount);
        }
    }

    public static HistogramMergedAggregate getHistogramMergedAggregate(List<Aggregate> aggregates)
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

    public static AggregateProfileNode getProfile(List<CharSource> profiles,
            double truncateLeafPercentage) throws IOException {
        AggregateProfileNode syntheticRootNode = AggregateProfileNode.createSyntheticRootNode();
        for (CharSource profile : profiles) {
            String profileContent = profile.read();
            if (profileContent.equals(AggregateDao.OVERWRITTEN)) {
                continue;
            }
            AggregateProfileNode toBeMergedRootNode = ObjectMappers.readRequiredValue(mapper,
                    profileContent, AggregateProfileNode.class);
            syntheticRootNode.mergeMatchedNode(toBeMergedRootNode);
        }
        if (truncateLeafPercentage != 0) {
            int minSamples = (int) (syntheticRootNode.getSampleCount() * truncateLeafPercentage);
            truncateLeafs(syntheticRootNode, minSamples);
        }
        if (syntheticRootNode.getChildNodes().size() == 1) {
            // strip off synthetic root node
            return syntheticRootNode.getChildNodes().get(0);
        } else {
            return syntheticRootNode;
        }
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
