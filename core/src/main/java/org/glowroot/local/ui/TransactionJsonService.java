/*
 * Copyright 2011-2015 the original author or authors.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.LazyHistogram;
import org.glowroot.collector.TransactionSummary;
import org.glowroot.collector.TransactionSummaryMarshaler;
import org.glowroot.common.Clock;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.TransactionSummaryQuery;
import org.glowroot.local.store.AggregateDao.TransactionSummarySortOrder;
import org.glowroot.local.store.AggregateMerging;
import org.glowroot.local.store.AggregateMerging.HistogramMergedAggregate;
import org.glowroot.local.store.AggregateMerging.MetricMergedAggregate;
import org.glowroot.local.store.AggregateMerging.ThreadInfoAggregate;
import org.glowroot.local.store.AggregateMetric;
import org.glowroot.local.store.AggregateProfileNode;
import org.glowroot.local.store.ImmutableTransactionSummaryQuery;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.ThreadInfoAggregateMarshaler;
import org.glowroot.local.store.TraceDao;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class TransactionJsonService {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final double MICROSECONDS_PER_SECOND = 1000000.0;

    private final TransactionCommonService transactionCommonService;
    private final TraceDao traceDao;
    private final Clock clock;

    private final long fixedAggregateIntervalMillis;
    private final long fixedAggregateRollupMillis;

    TransactionJsonService(TransactionCommonService transactionCommonService, TraceDao traceDao,
            Clock clock, long fixedAggregateIntervalSeconds, long fixedAggregateRollupSeconds) {
        this.transactionCommonService = transactionCommonService;
        this.traceDao = traceDao;
        this.clock = clock;
        this.fixedAggregateIntervalMillis = fixedAggregateIntervalSeconds * 1000;
        this.fixedAggregateRollupMillis = fixedAggregateRollupSeconds * 1000;
    }

    @GET("/backend/transaction/overview")
    String getOverview(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);

        List<Aggregate> aggregates = transactionCommonService.getAggregates(
                request.transactionType(), request.transactionName(), request.from(), request.to());
        List<DataSeries> dataSeriesList = getDataSeriesForOverviewChart(request, aggregates);
        Map<Long, Long> transactionCounts = getTransactionCounts(aggregates);
        if (!aggregates.isEmpty() && aggregates.get(0).captureTime() == request.from()) {
            // the left most aggregate is not really in the requested interval since it is for
            // prior capture times
            aggregates = aggregates.subList(1, aggregates.size());
        }
        HistogramMergedAggregate histogramMergedAggregate =
                AggregateMerging.getHistogramMergedAggregate(aggregates);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeObjectField("transactionCounts", transactionCounts);
        jg.writeObjectField("mergedAggregate", histogramMergedAggregate);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private Map<Long, Long> getTransactionCounts(List<Aggregate> aggregates) {
        Map<Long, Long> transactionCounts = Maps.newHashMap();
        for (Aggregate aggregate : aggregates) {
            transactionCounts.put(aggregate.captureTime(), aggregate.transactionCount());
        }
        return transactionCounts;
    }

    @GET("/backend/transaction/metrics")
    String getMetrics(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);

        List<Aggregate> aggregates = transactionCommonService.getAggregates(
                request.transactionType(), request.transactionName(), request.from(), request.to());
        List<DataSeries> dataSeriesList = getDataSeriesForMetricsChart(request, aggregates);
        Map<Long, Long> transactionCounts = getTransactionCounts(aggregates);
        if (!aggregates.isEmpty() && aggregates.get(0).captureTime() == request.from()) {
            // the left most aggregate is not really in the requested interval since it is for
            // prior capture times
            aggregates = aggregates.subList(1, aggregates.size());
        }
        MetricMergedAggregate metricMergedAggregate =
                AggregateMerging.getMetricMergedAggregate(aggregates);
        ThreadInfoAggregate threadInfoAggregate =
                AggregateMerging.getThreadInfoAggregate(aggregates);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeObjectField("transactionCounts", transactionCounts);
        jg.writeObjectField("mergedAggregate", metricMergedAggregate);
        if (!threadInfoAggregate.isEmpty()) {
            jg.writeFieldName("threadInfoAggregate");
            ThreadInfoAggregateMarshaler.marshal(jg, threadInfoAggregate);
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/profile")
    String getProfile(String queryString) throws Exception {
        TransactionProfileRequest request =
                QueryStrings.decode(queryString, TransactionProfileRequest.class);

        AggregateProfileNode profile = transactionCommonService.getProfile(
                request.transactionType(), request.transactionName(), request.from(), request.to(),
                request.truncateLeafPercentage());
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeObject(profile);
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/summaries")
    String getSummaries(String queryString) throws Exception {
        TransactionSummaryRequest request =
                QueryStrings.decode(queryString, TransactionSummaryRequest.class);

        TransactionSummary overallSummary = transactionCommonService.readOverallSummary(
                request.transactionType(), request.from() + 1, request.to());

        TransactionSummaryQuery query = ImmutableTransactionSummaryQuery.builder()
                .transactionType(request.transactionType())
                .from(request.from() + 1)
                .to(request.to())
                .sortOrder(request.sortOrder())
                .limit(request.limit())
                .build();
        QueryResult<TransactionSummary> queryResult =
                transactionCommonService.readTransactionSummaries(query);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeFieldName("overall");
        TransactionSummaryMarshaler.marshal(jg, overallSummary);
        jg.writeFieldName("transactions");
        TransactionSummaryMarshaler.instance().marshalIterable(jg, queryResult.records());
        jg.writeBooleanField("moreAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/tab-bar-data")
    String getTabBarData(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);

        String transactionName = request.transactionName();
        long profileSampleCount = transactionCommonService.getProfileSampleCount(
                request.transactionType(), transactionName, request.from(), request.to());
        long traceCount;
        if (transactionName == null) {
            traceCount = traceDao.readOverallCount(request.transactionType(), request.from(),
                    request.to());
        } else {
            traceCount = traceDao.readTransactionCount(request.transactionType(),
                    transactionName, request.from(), request.to());
        }

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeNumberField("profileSampleCount", profileSampleCount);
        jg.writeNumberField("traceCount", traceCount);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/flame-graph")
    String getFlameGraph(String queryString) throws Exception {
        FlameGraphRequest request = QueryStrings.decode(queryString, FlameGraphRequest.class);
        AggregateProfileNode profile = transactionCommonService.getProfile(
                request.transactionType(), request.transactionName(), request.from(),
                request.to(), request.truncateLeafPercentage());
        AggregateProfileNode interestingNode = profile;
        while (interestingNode.getChildNodes().size() == 1) {
            interestingNode = interestingNode.getChildNodes().get(0);
        }
        if (interestingNode.getChildNodes().isEmpty()) {
            // only a single branch through entire tree
            interestingNode = profile;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectFieldStart("");
        jg.writeNumberField("svUnique", 0);
        jg.writeNumberField("svTotal", interestingNode.getSampleCount());
        jg.writeObjectFieldStart("svChildren");
        writeFlameGraphNode(interestingNode, jg);
        jg.writeEndObject();
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private List<DataSeries> getDataSeriesForOverviewChart(TransactionDataRequest request,
            List<Aggregate> aggregates) throws Exception {
        if (aggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        DataSeriesHelper dataSeriesHelper =
                new DataSeriesHelper(clock, getDataPointIntervalMillis(request));
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        DataSeries dataSeries1 = new DataSeries("50th percentile");
        DataSeries dataSeries2 = new DataSeries("95th percentile");
        DataSeries dataSeries3 = new DataSeries("99th percentile");
        dataSeriesList.add(dataSeries1);
        dataSeriesList.add(dataSeries2);
        dataSeriesList.add(dataSeries3);
        Aggregate lastAggregate = null;
        for (Aggregate aggregate : aggregates) {
            if (lastAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslope(request.from(), aggregate.captureTime(),
                        dataSeriesList, null);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastAggregate.captureTime(),
                        aggregate.captureTime(), dataSeriesList, null);
            }
            lastAggregate = aggregate;
            LazyHistogram histogram = new LazyHistogram();
            histogram.decodeFromByteBuffer(ByteBuffer.wrap(aggregate.histogram()));
            dataSeries1.add(aggregate.captureTime(),
                    histogram.getValueAtPercentile(50) / MICROSECONDS_PER_SECOND);
            dataSeries2.add(aggregate.captureTime(),
                    histogram.getValueAtPercentile(95) / MICROSECONDS_PER_SECOND);
            dataSeries3.add(aggregate.captureTime(),
                    histogram.getValueAtPercentile(99) / MICROSECONDS_PER_SECOND);
        }
        if (lastAggregate != null) {
            dataSeriesHelper.addFinalDownslope(request.to(), dataSeriesList, null,
                    lastAggregate.captureTime());
        }
        return dataSeriesList;
    }

    private List<DataSeries> getDataSeriesForMetricsChart(TransactionDataRequest request,
            List<Aggregate> aggregates) throws IOException {
        if (aggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        List<StackedPoint> stackedPoints = Lists.newArrayList();
        for (Aggregate aggregate : aggregates) {
            stackedPoints.add(StackedPoint.create(aggregate));
        }
        return getMetricDataSeries(request, stackedPoints);
    }

    private List<DataSeries> getMetricDataSeries(TransactionDataRequest request,
            List<StackedPoint> stackedPoints) {
        DataSeriesHelper dataSeriesHelper =
                new DataSeriesHelper(clock, getDataPointIntervalMillis(request));
        final int topX = 5;
        List<String> metricNames = getTopMetricNames(stackedPoints, topX + 1);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (int i = 0; i < Math.min(metricNames.size(), topX); i++) {
            dataSeriesList.add(new DataSeries(metricNames.get(i)));
        }
        // need 'other' data series even if < topX metrics in order to capture root metrics,
        // e.g. time spent in 'servlet' metric but not i)n any nested metric
        DataSeries otherDataSeries = new DataSeries(null);
        Aggregate lastAggregate = null;
        for (StackedPoint stackedPoint : stackedPoints) {
            Aggregate aggregate = stackedPoint.getAggregate();
            if (lastAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslope(request.from(), aggregate.captureTime(),
                        dataSeriesList, otherDataSeries);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastAggregate.captureTime(),
                        aggregate.captureTime(), dataSeriesList, otherDataSeries);
            }
            lastAggregate = aggregate;
            MutableLongMap<String> stackedMetrics = stackedPoint.getStackedMetrics();
            long totalOtherMicros = aggregate.totalMicros();
            for (DataSeries dataSeries : dataSeriesList) {
                MutableLong totalMicros = stackedMetrics.get(dataSeries.getName());
                if (totalMicros == null) {
                    dataSeries.add(aggregate.captureTime(), 0);
                } else {
                    // convert to average seconds
                    dataSeries.add(aggregate.captureTime(),
                            (totalMicros.longValue() / (double) aggregate.transactionCount())
                                    / MICROSECONDS_PER_SECOND);
                    totalOtherMicros -= totalMicros.longValue();
                }
            }
            if (aggregate.transactionCount() == 0) {
                otherDataSeries.add(aggregate.captureTime(), 0);
            } else {
                // convert to average seconds
                otherDataSeries.add(aggregate.captureTime(),
                        (totalOtherMicros / (double) aggregate.transactionCount())
                                / MICROSECONDS_PER_SECOND);
            }
        }
        if (lastAggregate != null) {
            dataSeriesHelper.addFinalDownslope(request.to(), dataSeriesList, otherDataSeries,
                    lastAggregate.captureTime());
        }
        dataSeriesList.add(otherDataSeries);
        return dataSeriesList;
    }

    private long getDataPointIntervalMillis(TransactionDataRequest request) {
        if (request.to() - request.from() > AggregateDao.ROLLUP_THRESHOLD_MILLIS) {
            return fixedAggregateRollupMillis;
        } else {
            return fixedAggregateIntervalMillis;
        }
    }

    // calculate top 5 metrics
    private static List<String> getTopMetricNames(List<StackedPoint> stackedPoints, int topX) {
        MutableLongMap<String> metricTotals = new MutableLongMap<String>();
        for (StackedPoint stackedPoint : stackedPoints) {
            for (Entry<String, MutableLong> entry : stackedPoint.getStackedMetrics().entrySet()) {
                metricTotals.add(entry.getKey(), entry.getValue().longValue());
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
        List<String> metricNames = Lists.newArrayList();
        List<Entry<String, MutableLong>> topMetricTotals =
                valueOrdering.greatestOf(metricTotals.entrySet(), topX);
        for (Entry<String, MutableLong> entry : topMetricTotals) {
            metricNames.add(entry.getKey());
        }
        return metricNames;
    }

    private static void writeFlameGraphNode(AggregateProfileNode node, JsonGenerator jg)
            throws IOException {
        jg.writeObjectFieldStart(Strings.nullToEmpty(node.getStackTraceElement()));
        long svUnique = 0;
        if (node.getLeafThreadState() != null) {
            svUnique = node.getSampleCount();
            for (AggregateProfileNode childNode : node.getChildNodes()) {
                svUnique -= childNode.getSampleCount();
            }
        }
        jg.writeNumberField("svUnique", svUnique);
        jg.writeNumberField("svTotal", node.getSampleCount());
        jg.writeObjectFieldStart("svChildren");
        for (AggregateProfileNode childNode : node.getChildNodes()) {
            writeFlameGraphNode(childNode, jg);
        }
        jg.writeEndObject();
        jg.writeEndObject();
    }

    private static class StackedPoint {

        private final Aggregate aggregate;
        // stacked metric values only include time spent as a leaf node in the metric tree
        private final MutableLongMap<String> stackedMetrics;

        private static StackedPoint create(Aggregate aggregate) throws IOException {
            String metrics = aggregate.metrics();
            MutableLongMap<String> stackedMetrics = new MutableLongMap<String>();
            AggregateMetric syntheticRootMetric = mapper.readValue(metrics, AggregateMetric.class);
            // skip synthetic root metric
            for (AggregateMetric realRootMetric : syntheticRootMetric.getNestedMetrics()) {
                // skip real root metrics
                for (AggregateMetric topLevelMetric : realRootMetric.getNestedMetrics()) {
                    // traverse tree starting at top-level (under root) metrics
                    addToStackedMetric(topLevelMetric, stackedMetrics);
                }
            }
            return new StackedPoint(aggregate, stackedMetrics);
        }

        private StackedPoint(Aggregate aggregate, MutableLongMap<String> stackedMetrics) {
            this.aggregate = aggregate;
            this.stackedMetrics = stackedMetrics;
        }

        private Aggregate getAggregate() {
            return aggregate;
        }

        private MutableLongMap<String> getStackedMetrics() {
            return stackedMetrics;
        }

        private static void addToStackedMetric(AggregateMetric metric,
                MutableLongMap<String> stackedMetrics) {
            long totalNestedMicros = 0;
            for (AggregateMetric nestedMetric : metric.getNestedMetrics()) {
                totalNestedMicros += nestedMetric.getTotalMicros();
                addToStackedMetric(nestedMetric, stackedMetrics);
            }
            stackedMetrics.add(metric.getName(), metric.getTotalMicros() - totalNestedMicros);
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

    @Value.Immutable
    @Json.Marshaled
    abstract static class TransactionSummaryRequest {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract TransactionSummarySortOrder sortOrder();
        abstract int limit();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class TransactionDataRequest {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class TransactionProfileRequest {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
        abstract double truncateLeafPercentage();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class FlameGraphRequest {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
        abstract double truncateLeafPercentage();
    }
}
