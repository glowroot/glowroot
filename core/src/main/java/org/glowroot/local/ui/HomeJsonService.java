/*
 * Copyright 2011-2014 the original author or authors.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CaseFormat;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.TransactionPoint;
import org.glowroot.common.Clock;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.Overall;
import org.glowroot.local.store.Transaction;
import org.glowroot.local.store.TransactionPointDao;
import org.glowroot.local.store.TransactionPointDao.SortDirection;
import org.glowroot.local.store.TransactionPointDao.TransactionSortColumn;
import org.glowroot.markers.Singleton;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to read transaction point data, bound to /backend/home.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class HomeJsonService {

    private static final Logger logger = LoggerFactory.getLogger(HomeJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TransactionPointDao transactionPointDao;
    private final Clock clock;
    private final long fixedAggregationIntervalMillis;

    HomeJsonService(TransactionPointDao transactionPointDao, Clock clock,
            long fixedAggregationIntervalSeconds) {
        this.transactionPointDao = transactionPointDao;
        this.clock = clock;
        fixedAggregationIntervalMillis = fixedAggregationIntervalSeconds * 1000;
    }

    @GET("/backend/home/stacked")
    String getStacked(String content) throws IOException {
        logger.debug("getStacked(): content={}", content);
        CommonRequest request =
                ObjectMappers.readRequiredValue(mapper, content, CommonRequest.class);
        List<TransactionPoint> transactionPoints;
        String transactionName = request.getTransactionName();
        if (transactionName == null) {
            transactionPoints = transactionPointDao.readOverallPoints("", request.getFrom(),
                    request.getTo());
        } else {
            transactionPoints = transactionPointDao.readTransactionPoints("", transactionName,
                    request.getFrom(), request.getTo());
        }
        List<StackedPoint> stackedPoints = Lists.newArrayList();
        for (TransactionPoint transactionPoint : transactionPoints) {
            stackedPoints.add(StackedPoint.create(transactionPoint));
        }
        List<String> metricNames = getTopMetricNames(stackedPoints);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (String metricName : metricNames) {
            dataSeriesList.add(new DataSeries(metricName));
        }
        DataSeries otherDataSeries = new DataSeries(null);
        TransactionPoint lastTransactionPoint = null;
        for (StackedPoint stackedPoint : stackedPoints) {
            TransactionPoint transactionPoint = stackedPoint.getTransactionPoint();
            if (lastTransactionPoint == null) {
                // first transaction point
                addInitialUpslope(request.getFrom(), transactionPoint, dataSeriesList,
                        otherDataSeries);
            } else {
                addGap(lastTransactionPoint, transactionPoint, dataSeriesList, otherDataSeries);
            }
            lastTransactionPoint = transactionPoint;
            MutableLongMap<String> stackedMetrics = stackedPoint.getStackedMetrics();
            long totalOtherMicros = transactionPoint.getTotalMicros();
            for (DataSeries dataSeries : dataSeriesList) {
                MutableLong totalMicros = stackedMetrics.get(dataSeries.getName());
                if (totalMicros == null) {
                    dataSeries.add(transactionPoint.getCaptureTime(), 0);
                } else {
                    dataSeries.add(transactionPoint.getCaptureTime(),
                            totalMicros.longValue() / transactionPoint.getCount());
                    totalOtherMicros -= totalMicros.longValue();
                }
            }
            if (transactionPoint.getCount() == 0) {
                otherDataSeries.add(transactionPoint.getCaptureTime(), 0);
            } else {
                otherDataSeries.add(transactionPoint.getCaptureTime(),
                        totalOtherMicros / transactionPoint.getCount());
            }
        }
        if (lastTransactionPoint != null) {
            addFinalDownslope(request, dataSeriesList, otherDataSeries, lastTransactionPoint);
        }
        dataSeriesList.add(otherDataSeries);
        return mapper.writeValueAsString(dataSeriesList);
    }

    @GET("/backend/home/transactions")
    String getTransactions(String content) throws IOException {
        logger.debug("getTransactions(): content={}", content);
        TransactionRequest request =
                ObjectMappers.readRequiredValue(mapper, content, TransactionRequest.class);
        Overall overall = transactionPointDao.readOverall("", request.getFrom(), request.getTo());
        String sortAttribute =
                CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, request.getSortAttribute());
        TransactionSortColumn sortColumn = TransactionSortColumn.valueOf(sortAttribute);
        List<Transaction> transactions = transactionPointDao.readTransactions("",
                request.getFrom(), request.getTo(), sortColumn, request.getSortDirection(),
                request.getLimit());
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("overall", overall);
        jg.writeObjectField("transactions", transactions);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/home/profile")
    String getProfile(String content) throws IOException {
        logger.debug("getProfile(): content={}", content);
        CommonRequest request =
                ObjectMappers.readRequiredValue(mapper, content, CommonRequest.class);
        String transactionName = request.getTransactionName();
        if (transactionName == null) {
            throw new IllegalStateException("Required field missing: 'transactionName'");
        }
        List<CharSource> profiles = transactionPointDao.readProfiles("", transactionName,
                request.getFrom(), request.getTo());
        TransactionProfileNode syntheticRootNode = new TransactionProfileNode();
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
                mergeChildNodeIntoParent(toBeMergedRootNode, syntheticRootNode);
            }
        }
        if (syntheticRootNode.getChildNodes().size() == 0) {
            return "null"; // json null
        } else if (syntheticRootNode.getChildNodes().size() == 1) {
            // strip off synthetic root node since only one real root node
            return mapper.writeValueAsString(syntheticRootNode.getChildNodes().get(0));
        } else {
            return mapper.writeValueAsString(syntheticRootNode);
        }
    }

    // calculate top 5 metrics
    private List<String> getTopMetricNames(List<StackedPoint> stackedPoints) {
        MutableLongMap<String> totalMicros = new MutableLongMap<String>();
        for (StackedPoint stackedPoint : stackedPoints) {
            for (Entry<String, MutableLong> entry : stackedPoint.getStackedMetrics().entrySet()) {
                totalMicros.add(entry.getKey(), entry.getValue().longValue());
            }
        }
        Ordering<Entry<String, MutableLong>> valueOrdering = Ordering.natural().onResultOf(
                new Function<Entry<String, MutableLong>, Long>() {
                    @Override
                    public Long apply(@Nullable Entry<String, MutableLong> entry) {
                        checkNotNull(entry);
                        return entry.getValue().longValue();
                    }
                });
        final int topX = 5;
        List<String> metricNames = Lists.newArrayList();
        for (Entry<String, MutableLong> entry : valueOrdering
                .greatestOf(totalMicros.entrySet(), topX)) {
            metricNames.add(entry.getKey());
        }
        return metricNames;
    }

    private void addInitialUpslope(long requestFrom, TransactionPoint transactionPoint,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries) {
        long millisecondsFromEdge = transactionPoint.getCaptureTime() - requestFrom;
        if (millisecondsFromEdge < fixedAggregationIntervalMillis / 2) {
            return;
        }
        // bring up from zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(transactionPoint.getCaptureTime() - fixedAggregationIntervalMillis / 2,
                    0);
        }
        otherDataSeries.add(transactionPoint.getCaptureTime() - fixedAggregationIntervalMillis / 2,
                0);
    }

    private void addGap(TransactionPoint lastTransactionPoint, TransactionPoint transactionPoint,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries) {
        long millisecondsSinceLastPoint =
                transactionPoint.getCaptureTime() - lastTransactionPoint.getCaptureTime();
        if (millisecondsSinceLastPoint < fixedAggregationIntervalMillis * 1.5) {
            return;
        }
        // gap between points, bring down to zero and then back up from zero to show gap
        for (DataSeries dataSeries : dataSeriesList) {
            addGap(dataSeries, lastTransactionPoint, transactionPoint);
        }
        addGap(otherDataSeries, lastTransactionPoint, transactionPoint);
    }

    private void addGap(DataSeries dataSeries, TransactionPoint lastTransactionPoint,
            TransactionPoint transactionPoint) {
        dataSeries.add(lastTransactionPoint.getCaptureTime()
                + fixedAggregationIntervalMillis / 2, 0);
        dataSeries.addNull();
        dataSeries.add(transactionPoint.getCaptureTime()
                - fixedAggregationIntervalMillis / 2, 0);
    }

    private void addFinalDownslope(CommonRequest request, List<DataSeries> dataSeriesList,
            DataSeries otherDataSeries, TransactionPoint lastTransactionPoint) {
        long millisecondsAgoFromNow =
                clock.currentTimeMillis() - lastTransactionPoint.getCaptureTime();
        if (millisecondsAgoFromNow < fixedAggregationIntervalMillis * 1.5) {
            return;
        }
        long millisecondsFromEdge = request.getTo() - lastTransactionPoint.getCaptureTime();
        if (millisecondsFromEdge < fixedAggregationIntervalMillis / 2) {
            return;
        }
        // bring down to zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(lastTransactionPoint.getCaptureTime()
                    + fixedAggregationIntervalMillis / 2, 0);
        }
        otherDataSeries.add(lastTransactionPoint.getCaptureTime()
                + fixedAggregationIntervalMillis / 2, 0);
    }

    private void mergeMatchedNode(TransactionProfileNode toBeMergedNode,
            TransactionProfileNode node) {
        node.incrementSampleCount(toBeMergedNode.getSampleCount());
        // the metric names for a given stack element should always match, unless
        // the line numbers aren't available and overloaded methods are matched up, or
        // the stack trace was captured while one of the synthetic $metric$ methods was
        // executing in which case one of the metric names may be a subset of the other,
        // in which case, the superset wins:
        List<String> metricNames = toBeMergedNode.getMetricNames();
        if (metricNames != null
                && metricNames.size() > node.getMetricNames().size()) {
            node.setMetricNames(metricNames);
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

    private static class CommonRequest {

        private final long from;
        private final long to;
        @Nullable
        private final String transactionName;

        @JsonCreator
        CommonRequest(@JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("transactionName") @Nullable String transactionName)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            this.from = from;
            this.to = to;
            this.transactionName = transactionName;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }

        @Nullable
        private String getTransactionName() {
            return transactionName;
        }
    }

    // this is used for stacked response
    private static class DataSeries {

        // null is used for 'Other' data series
        @JsonProperty
        @Nullable
        private final String name;
        @JsonProperty
        private final List<Number/*@Nullable*/[]> data = Lists.newArrayList();

        private DataSeries(@Nullable String name) {
            this.name = name;
        }

        @Nullable
        private String getName() {
            return name;
        }

        private void add(long captureTime, double averageMicros) {
            // convert microseconds to seconds
            data.add(new Number[] {captureTime, averageMicros / 1000000});
        }

        private void addNull() {
            data.add(null);
        }
    }

    private static class TransactionRequest {

        private final long from;
        private final long to;
        private final String sortAttribute;
        private final SortDirection sortDirection;
        private final int limit;

        @JsonCreator
        TransactionRequest(
                @JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("sortAttribute") @Nullable String sortAttribute,
                @JsonProperty("sortDirection") @Nullable SortDirection sortDirection,
                @JsonProperty("limit") @Nullable Integer limit)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            checkRequiredProperty(sortAttribute, "sortAttribute");
            checkRequiredProperty(sortDirection, "sortDirection");
            checkRequiredProperty(limit, "limit");
            this.from = from;
            this.to = to;
            this.sortAttribute = sortAttribute;
            this.sortDirection = sortDirection;
            this.limit = limit;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }

        private String getSortAttribute() {
            return sortAttribute;
        }

        private SortDirection getSortDirection() {
            return sortDirection;
        }

        private int getLimit() {
            return limit;
        }
    }

    private static class StackedPoint {

        private final TransactionPoint transactionPoint;
        // flattened metric values only include time spent as a leaf node in the metric tree
        private final MutableLongMap<String> stackedMetrics;

        private static StackedPoint create(TransactionPoint transactionPoint)
                throws IOException {
            String metrics = transactionPoint.getMetrics();
            if (metrics == null) {
                return new StackedPoint(transactionPoint, new MutableLongMap<String>());
            }
            // don't need thread safety, but guessing AtomicLongMap is faster since doesn't require
            //
            MutableLongMap<String> stackedMetrics = new MutableLongMap<String>();
            TransactionMetric syntheticRootTransactionMetric;
            syntheticRootTransactionMetric =
                    mapper.readValue(metrics, TransactionMetric.class);
            // skip synthetic root metric
            for (TransactionMetric rootMetric : syntheticRootTransactionMetric.getNestedMetrics()) {
                // skip root metrics
                for (TransactionMetric topLevelMetric : rootMetric.getNestedMetrics()) {
                    // traverse tree starting at top-level (under root) metrics
                    for (TransactionMetric metric : TransactionMetric.TRAVERSER
                            .preOrderTraversal(topLevelMetric)) {
                        long totalNestedMicros = 0;
                        for (TransactionMetric nestedMetric : metric.getNestedMetrics()) {
                            totalNestedMicros += nestedMetric.getTotalMicros();
                        }
                        stackedMetrics.add(metric.getName(),
                                metric.getTotalMicros() - totalNestedMicros);
                    }
                }
            }
            return new StackedPoint(transactionPoint, stackedMetrics);
        }

        private StackedPoint(TransactionPoint transactionPoint,
                MutableLongMap<String> stackedMetrics) {
            this.transactionPoint = transactionPoint;
            this.stackedMetrics = stackedMetrics;
        }

        private TransactionPoint getTransactionPoint() {
            return transactionPoint;
        }

        private MutableLongMap<String> getStackedMetrics() {
            return stackedMetrics;
        }
    }

    // by using MutableLong, two operations (get/put) are not required for each increment,
    // instead just a single get is needed (except for first delta)
    //
    // not thread safe, for thread safety use guava's AtomicLongMap
    @SuppressWarnings("serial")
    private static class MutableLongMap<K> extends HashMap<K, MutableLong> {
        private void add(K key, long delta) {
            MutableLong existing = get(key);
            if (existing == null) {
                put(key, new MutableLong(delta));
            } else {
                existing.value += delta;
            }
        }
    }

    private static class MutableLong {
        private long value;
        private MutableLong(long value) {
            this.value = value;
        }
        private long longValue() {
            return value;
        }
    }
}
