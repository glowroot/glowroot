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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import com.google.common.io.CharSource;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.Existence;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.markers.UsedByJsonBinding;

class AggregateCommonService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AggregateDao aggregateDao;

    AggregateCommonService(AggregateDao aggregateDao) {
        this.aggregateDao = aggregateDao;
    }

    MergedAggregate getMergedAggregate(List<Aggregate> aggregates) throws IOException {
        long totalMicros = 0;
        long count = 0;
        AggregateMetric syntheticRootMetric = AggregateMetric.createSyntheticRootMetric();
        Existence profileExistence = Existence.NO;
        long profileSampleCount = 0;
        for (Aggregate aggregate : aggregates) {
            totalMicros += aggregate.totalMicros();
            count += aggregate.transactionCount();
            AggregateMetric toBeMergedSyntheticRootMetric =
                    mapper.readValue(aggregate.metrics(), AggregateMetric.class);
            mergeMatchedMetric(toBeMergedSyntheticRootMetric, syntheticRootMetric);
            if (profileExistence == Existence.NO) {
                profileExistence = aggregate.profileExistence();
            } else if (profileExistence == Existence.EXPIRED
                    && aggregate.profileExistence() == Existence.YES) {
                profileExistence = Existence.YES;
            }
            if (aggregate.profileExistence() == Existence.YES) {
                profileSampleCount += aggregate.profileSampleCount();
            }
        }
        AggregateMetric rootMetric = syntheticRootMetric;
        if (syntheticRootMetric.getNestedMetrics().size() == 1) {
            rootMetric = syntheticRootMetric.getNestedMetrics().get(0);
        }
        return new MergedAggregate(totalMicros, count, rootMetric, profileExistence,
                profileSampleCount);
    }

    @Nullable
    AggregateProfileNode getProfile(String transactionType, @Nullable String transactionName,
            long from, long to, double truncateLeafPercentage) throws Exception {
        List<CharSource> profiles;
        if (transactionName == null) {
            profiles = aggregateDao.readOverallProfiles(transactionType, from, to);
        } else {
            profiles = aggregateDao.readTransactionProfiles(transactionType, transactionName, from,
                    to);
        }
        AggregateProfileNode syntheticRootNode = AggregateProfileNode.createSyntheticRootNode();
        for (CharSource profile : profiles) {
            AggregateProfileNode toBeMergedRootNode = ObjectMappers.readRequiredValue(mapper,
                    profile.read(), AggregateProfileNode.class);
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
        if (syntheticRootNode.getChildNodes().isEmpty()) {
            return null;
        } else if (syntheticRootNode.getChildNodes().size() == 1) {
            // strip off synthetic root node since only one real root node
            return syntheticRootNode.getChildNodes().get(0);
        } else {
            return syntheticRootNode;
        }
    }

    private void mergeMatchedMetric(AggregateMetric toBeMergedMetric, AggregateMetric metric) {
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

    private void mergeMatchedNode(AggregateProfileNode toBeMergedNode, AggregateProfileNode node) {
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

    private void mergeChildNodeIntoParent(AggregateProfileNode toBeMergedChildNode,
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

    private boolean matches(AggregateProfileNode node1, AggregateProfileNode node2) {
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

    // MergedAggregate could use @Value.Immutable, but it's not technically immutable since it
    // contains non-immutable state (AggregateMetric)
    @UsedByJsonBinding
    public static class MergedAggregate {

        private final long totalMicros;
        private final long count;
        private final AggregateMetric rootMetric;
        private final Existence profileExistence;
        private final long profileSampleCount;

        private MergedAggregate(long totalMicros, long count, AggregateMetric rootMetric,
                Existence profileExistence, long profileSampleCount) {
            this.totalMicros = totalMicros;
            this.count = count;
            this.rootMetric = rootMetric;
            this.profileExistence = profileExistence;
            this.profileSampleCount = profileSampleCount;
        }

        public long getTotalMicros() {
            return totalMicros;
        }

        public long getCount() {
            return count;
        }

        public AggregateMetric getMetrics() {
            return rootMetric;
        }

        public String getProfileExistence() {
            return profileExistence.name().toLowerCase(Locale.ENGLISH);
        }

        public long getProfileSampleCount() {
            return profileSampleCount;
        }
    }
}
