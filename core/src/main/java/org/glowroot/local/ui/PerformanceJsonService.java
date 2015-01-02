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
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.immutables.value.Json;
import org.immutables.value.Value;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import org.glowroot.collector.Aggregate;
import org.glowroot.common.Clock;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.PerformanceSummaryQuery;
import org.glowroot.local.store.AggregateDao.PerformanceSummarySortOrder;
import org.glowroot.local.store.ImmutablePerformanceSummaryQuery;
import org.glowroot.local.store.PerformanceSummary;
import org.glowroot.local.store.PerformanceSummaryMarshaler;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.TraceDao;
import org.glowroot.local.ui.AggregateCommonService.MergedAggregate;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class PerformanceJsonService {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MICROSECONDS_PER_SECOND = 1000000;

    private final AggregateCommonService aggregateCommonService;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final DataSeriesHelper dataSeriesHelper;

    PerformanceJsonService(AggregateCommonService aggregateCommonService,
            AggregateDao aggregateDao,
            TraceDao traceDao, Clock clock, long fixedAggregateIntervalSeconds) {
        this.aggregateCommonService = aggregateCommonService;
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        dataSeriesHelper = new DataSeriesHelper(clock, fixedAggregateIntervalSeconds * 1000);
    }

    @GET("/backend/performance/data")
    String getData(String queryString) throws Exception {
        PerformanceDataRequest request =
                QueryStrings.decode(queryString, PerformanceDataRequest.class);

        List<Aggregate> aggregates = getAggregates(request);
        List<StackedPoint> stackedPoints = getStackedPoints(aggregates);
        List<DataSeries> dataSeriesList = getMetricDataSeries(request, stackedPoints);
        MergedAggregate mergedAggregate = aggregateCommonService.getMergedAggregate(aggregates);
        long traceCount = getTraceCount(request);

        PerformanceSummary overallSummary = aggregateDao.readOverallPerformanceSummary(
                request.transactionType(),
                request.from(), request.to());
        ImmutablePerformanceSummaryQuery query = ImmutablePerformanceSummaryQuery.builder()
                .transactionType(request.transactionType())
                .from(request.from())
                .to(request.to())
                .sortOrder(request.summarySortOrder())
                .limit(request.summaryLimit())
                .build();
        QueryResult<PerformanceSummary> queryResult =
                aggregateDao.readTransactionPerformanceSummaries(query);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeObjectField("mergedAggregate", mergedAggregate);
        jg.writeObjectField("traceCount", traceCount);
        jg.writeFieldName("overallSummary");
        PerformanceSummaryMarshaler.marshal(jg, overallSummary);
        jg.writeFieldName("transactionSummaries");
        PerformanceSummaryMarshaler.instance().marshalIterable(jg, queryResult.records());
        jg.writeBooleanField("moreSummariesAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/performance/summaries")
    String getSummaries(String queryString) throws Exception {
        PerformanceSummaryQuery query =
                QueryStrings.decode(queryString, PerformanceSummaryQuery.class);
        QueryResult<PerformanceSummary> queryResult =
                aggregateDao.readTransactionPerformanceSummaries(query);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeFieldName("transactionSummaries");
        PerformanceSummaryMarshaler.instance().marshalIterable(jg, queryResult.records());
        jg.writeBooleanField("moreSummariesAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/performance/profile")
    String getProfile(String queryString) throws Exception {
        ProfileRequest request = QueryStrings.decode(queryString, ProfileRequest.class);
        AggregateProfileNode profile = aggregateCommonService.getProfile(
                request.transactionType(), request.transactionName(), request.from(),
                request.to(), request.truncateLeafPercentage());
        if (profile == null) {
            // this should not happen as the user interface checks profile sample count before
            // sending this request
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND, "Profile data not found");
        }
        return mapper.writeValueAsString(profile);
    }

    @GET("/backend/performance/flame-graph")
    String getFlameGraph(String queryString) throws Exception {
        ProfileRequest request = QueryStrings.decode(queryString, ProfileRequest.class);
        AggregateProfileNode profile = aggregateCommonService.getProfile(
                request.transactionType(), request.transactionName(), request.from(),
                request.to(), request.truncateLeafPercentage());
        if (profile == null) {
            // this should not happen as the user interface checks profile sample count before
            // sending this request
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND, "Profile data not found");
        }
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

    private List<DataSeries> getMetricDataSeries(PerformanceDataRequest request,
            List<StackedPoint> stackedPoints) {
        final int topX = 5;
        List<String> metricNames = getTopMetricNames(stackedPoints, topX + 1);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (int i = 0; i < Math.min(metricNames.size(), topX); i++) {
            dataSeriesList.add(new DataSeries(metricNames.get(i)));
        }
        // need 'other' data series even if < topX metrics in order to capture root metrics,
        // e.g. time spent in 'servlet' metric but not in any nested metric
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
        if (!stackedPoints.isEmpty()) {
            dataSeriesList.add(otherDataSeries);
        }
        return dataSeriesList;
    }

    private List<Aggregate> getAggregates(PerformanceDataRequest request) throws SQLException {
        String transactionName = request.transactionName();
        if (transactionName == null) {
            return aggregateDao.readOverallAggregates(request.transactionType(), request.from(),
                    request.to());
        } else {
            return aggregateDao.readTransactionAggregates(request.transactionType(),
                    transactionName, request.from(), request.to());
        }
    }

    private List<StackedPoint> getStackedPoints(List<Aggregate> aggregates) throws IOException {
        List<StackedPoint> stackedPoints = Lists.newArrayList();
        for (Aggregate aggregate : aggregates) {
            stackedPoints.add(StackedPoint.create(aggregate));
        }
        return stackedPoints;
    }

    // calculate top 5 metrics
    private List<String> getTopMetricNames(List<StackedPoint> stackedPoints, int topX) {
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

    private long getTraceCount(PerformanceDataRequest request) throws SQLException {
        String transactionName = request.transactionName();
        if (transactionName == null) {
            return traceDao.readOverallCount(request.transactionType(), request.from(),
                    request.to());
        } else {
            return traceDao.readTransactionCount(request.transactionType(),
                    transactionName, request.from(), request.to());
        }
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
    abstract static class PerformanceDataRequest {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
        abstract PerformanceSummarySortOrder summarySortOrder();
        abstract int summaryLimit();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class ProfileRequest {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
        abstract double truncateLeafPercentage();
    }
}
