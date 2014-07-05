/*
 * Copyright 2014 the original author or authors.
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import com.google.common.io.CharSource;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.collector.Existence;
import org.glowroot.collector.TransactionPoint;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.TransactionPointDao;
import org.glowroot.markers.Singleton;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TransactionCommonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TransactionPointDao transactionPointDao;

    TransactionCommonService(TransactionPointDao transactionPointDao) {
        this.transactionPointDao = transactionPointDao;
    }

    TransactionHeader getTransactionHeader(@Nullable String transactionName, long from, long to)
            throws IOException {
        List<TransactionPoint> transactionPoints;
        if (transactionName == null) {
            transactionPoints = transactionPointDao.readOverallPoints("", from, to);
        } else {
            transactionPoints =
                    transactionPointDao.readTransactionPoints("", transactionName, from, to);
        }
        long totalMicros = 0;
        long count = 0;
        long errorCount = 0;
        SimpleTraceMetric syntheticRootNode = SimpleTraceMetric.createSyntheticRootNode();
        Existence profileExistence = Existence.NO;
        for (TransactionPoint transactionPoint : transactionPoints) {
            totalMicros += transactionPoint.getTotalMicros();
            count += transactionPoint.getCount();
            errorCount += transactionPoint.getErrorCount();
            SimpleTraceMetric toBeMergedSyntheticRootNode =
                    mapper.readValue(transactionPoint.getTraceMetrics(), SimpleTraceMetric.class);
            mergeMatchedNode(toBeMergedSyntheticRootNode, syntheticRootNode);
            if (profileExistence == Existence.NO) {
                profileExistence = transactionPoint.getProfileExistence();
            } else if (profileExistence == Existence.EXPIRED
                    && transactionPoint.getProfileExistence() == Existence.YES) {
                profileExistence = Existence.YES;
            }
        }
        SimpleTraceMetric traceMetrics = syntheticRootNode;
        if (syntheticRootNode.getNestedTraceMetrics().size() == 1) {
            traceMetrics = syntheticRootNode.getNestedTraceMetrics().get(0);
        }
        return new TransactionHeader(transactionName, from, to, totalMicros, count,
                errorCount, traceMetrics, profileExistence);
    }

    @Nullable
    TransactionProfileNode getProfile(String transactionName, long from, long to,
            double truncateLeafPercentage) throws IOException {
        List<CharSource> profiles =
                transactionPointDao.readProfiles("", transactionName, from, to);
        TransactionProfileNode syntheticRootNode = TransactionProfileNode.createSyntheticRootNode();
        for (CharSource profile : profiles) {
            if (profile == null) {
                continue;
            }
            TransactionProfileNode toBeMergedRootNode = ObjectMappers.readRequiredValue(mapper,
                    profile.read(), TransactionProfileNode.class);
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

    private void mergeMatchedNode(SimpleTraceMetric toBeMergedNode, SimpleTraceMetric node) {
        node.incrementCount(toBeMergedNode.getCount());
        node.incrementTotalMicros(toBeMergedNode.getTotalMicros());
        for (SimpleTraceMetric toBeMergedChildNode : toBeMergedNode.getNestedTraceMetrics()) {
            // for each to-be-merged child node look for a match
            SimpleTraceMetric foundMatchingChildNode = null;
            for (SimpleTraceMetric childNode : node.getNestedTraceMetrics()) {
                if (toBeMergedChildNode.getName().equals(childNode.getName())) {
                    foundMatchingChildNode = childNode;
                    break;
                }
            }
            if (foundMatchingChildNode == null) {
                node.getNestedTraceMetrics().add(toBeMergedChildNode);
            } else {
                mergeMatchedNode(toBeMergedChildNode, foundMatchingChildNode);
            }
        }
    }

    private void mergeMatchedNode(TransactionProfileNode toBeMergedNode,
            TransactionProfileNode node) {
        node.incrementSampleCount(toBeMergedNode.getSampleCount());
        // the trace metric names for a given stack element should always match, unless
        // the line numbers aren't available and overloaded methods are matched up, or
        // the stack trace was captured while one of the synthetic $trace$metric$ methods was
        // executing in which case one of the trace metric names may be a subset of the other,
        // in which case, the superset wins:
        List<String> traceMetrics = toBeMergedNode.getTraceMetrics();
        if (traceMetrics != null
                && traceMetrics.size() > node.getTraceMetrics().size()) {
            node.setTraceMetrics(traceMetrics);
        }
        for (TransactionProfileNode toBeMergedChildNode : toBeMergedNode.getChildNodes()) {
            mergeChildNodeIntoParent(toBeMergedChildNode, node);
        }
    }

    private void mergeChildNodeIntoParent(TransactionProfileNode toBeMergedChildNode,
            TransactionProfileNode parentNode) {
        // for each to-be-merged child node look for a match
        TransactionProfileNode foundMatchingChildNode = null;
        for (TransactionProfileNode childNode : parentNode.getChildNodes()) {
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

    private boolean matches(TransactionProfileNode node1, TransactionProfileNode node2) {
        return Objects.equal(node1.getStackTraceElement(), node2.getStackTraceElement())
                && Objects.equal(node1.getLeafThreadState(), node2.getLeafThreadState());
    }

    private static void truncateLeafs(TransactionProfileNode node, int minSamples) {
        for (Iterator<TransactionProfileNode> i = node.getChildNodes().iterator(); i.hasNext();) {
            TransactionProfileNode childNode = i.next();
            if (childNode.getSampleCount() < minSamples) {
                i.remove();
                node.setEllipsed();
            } else {
                truncateLeafs(childNode, minSamples);
            }
        }
    }

    @UsedByJsonBinding
    public static class TransactionHeader {

        @Nullable
        private final String transactionName;
        private final long from;
        private final long to;
        private final long totalMicros;
        private final long count;
        private final long errorCount;
        private final SimpleTraceMetric rootTraceMetric;
        private final Existence profileExistence;

        private TransactionHeader(@Nullable String transactionName, long from, long to,
                long totalMicros, long count, long errorCount,
                SimpleTraceMetric rootTraceMetric, Existence profileExistence) {
            this.transactionName = transactionName;
            this.from = from;
            this.to = to;
            this.totalMicros = totalMicros;
            this.count = count;
            this.errorCount = errorCount;
            this.rootTraceMetric = rootTraceMetric;
            this.profileExistence = profileExistence;
        }

        @Nullable
        public String getTransactionName() {
            return transactionName;
        }

        public long getFrom() {
            return from;
        }

        public long getTo() {
            return to;
        }

        public long getTotalMicros() {
            return totalMicros;
        }

        public long getCount() {
            return count;
        }

        public long getErrorCount() {
            return errorCount;
        }

        public SimpleTraceMetric getTraceMetrics() {
            return rootTraceMetric;
        }

        public Existence getProfileExistence() {
            return profileExistence;
        }
    }
}
