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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.PeekingIterator;
import com.google.common.io.CharStreams;
import org.immutables.value.Json;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Aggregate;
import org.glowroot.common.Clock;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.Summary;
import org.glowroot.local.store.SummaryMarshaler;
import org.glowroot.local.store.TraceDao;
import org.glowroot.local.ui.AggregateCommonService.MergedAggregate;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class AggregateJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AggregateJsonService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MICROSECONDS_PER_SECOND = 1000000;

    private final AggregateCommonService aggregateCommonService;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final Clock clock;
    private final long fixedAggregateIntervalMillis;

    AggregateJsonService(AggregateCommonService aggregateCommonService, AggregateDao aggregateDao,
            TraceDao traceDao, Clock clock, long fixedAggregateIntervalSeconds) {
        this.aggregateCommonService = aggregateCommonService;
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.clock = clock;
        fixedAggregateIntervalMillis = fixedAggregateIntervalSeconds * 1000;
    }

    @GET("/backend/performance/transactions")
    String getTransactions(String queryString) throws Exception {
        logger.debug("getTransactions(): queryString={}", queryString);
        AggregateRequestWithLimit request =
                QueryStrings.decode(queryString, AggregateRequestWithLimit.class);
        Summary overallSummary = aggregateDao.readOverallSummary(request.transactionType(),
                request.from(), request.to());
        Integer limit = request.limit();
        checkNotNull(limit);
        QueryResult<Summary> queryResult = aggregateDao.readTransactionSummaries(
                request.transactionType(), request.from(), request.to(), limit);
        List<Aggregate> overallAggregates = aggregateDao.readOverallAggregates(
                request.transactionType(), request.from(), request.to());

        final int topX = 5;
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        List<PeekingIterator<Aggregate>> transactionAggregatesList = Lists.newArrayList();
        for (int i = 0; i < Math.min(queryResult.records().size(), topX); i++) {
            String transactionName = queryResult.records().get(i).transactionName();
            checkNotNull(transactionName);
            dataSeriesList.add(new DataSeries(transactionName));
            transactionAggregatesList.add(Iterators.peekingIterator(
                    aggregateDao.readTransactionAggregates(request.transactionType(),
                            transactionName, request.from(), request.to()).iterator()));
        }

        DataSeries otherDataSeries = null;
        if (queryResult.records().size() > topX) {
            otherDataSeries = new DataSeries(null);
        }

        Aggregate lastOverallAggregate = null;
        for (Aggregate overallAggregate : overallAggregates) {
            if (lastOverallAggregate == null) {
                // first aggregate
                addInitialUpslope(request.from(), overallAggregate.captureTime(),
                        dataSeriesList, otherDataSeries);
            } else {
                addGapIfNeeded(lastOverallAggregate.captureTime(),
                        overallAggregate.captureTime(), dataSeriesList, otherDataSeries);
            }
            lastOverallAggregate = overallAggregate;

            long totalOtherMicros = overallAggregate.totalMicros();
            for (int i = 0; i < dataSeriesList.size(); i++) {
                PeekingIterator<Aggregate> transactionAggregates = transactionAggregatesList.get(i);
                Aggregate transactionAggregate = getNextAggregateIfMatching(transactionAggregates,
                        overallAggregate.captureTime());
                DataSeries dataSeries = dataSeriesList.get(i);
                if (transactionAggregate == null) {
                    dataSeries.add(overallAggregate.captureTime(), 0);
                } else {
                    // convert to average seconds
                    dataSeries.add(
                            overallAggregate.captureTime(),
                            (transactionAggregate.totalMicros() / (double) overallAggregate.transactionCount())
                                    / MICROSECONDS_PER_SECOND);
                    totalOtherMicros -= transactionAggregate.totalMicros();
                }
            }
            if (otherDataSeries != null) {
                otherDataSeries.add(overallAggregate.captureTime(),
                        (totalOtherMicros / (double) overallAggregate.transactionCount())
                                / MICROSECONDS_PER_SECOND);
            }
        }
        if (lastOverallAggregate != null) {
            addFinalDownslope(request.to(), dataSeriesList, otherDataSeries,
                    lastOverallAggregate.captureTime());
        }
        if (otherDataSeries != null) {
            dataSeriesList.add(otherDataSeries);
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeFieldName("overallSummary");
        SummaryMarshaler.marshal(jg, overallSummary);
        jg.writeFieldName("transactionSummaries");
        SummaryMarshaler.instance().marshalIterable(jg, queryResult.records());
        jg.writeBooleanField("moreAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/performance/metrics")
    String getMetrics(String queryString) throws Exception {
        logger.debug("getMetrics(): queryString={}", queryString);
        AggregateRequest request = QueryStrings.decode(queryString, AggregateRequest.class);
        List<Aggregate> aggregates;
        String transactionName = request.transactionName();
        if (transactionName == null) {
            aggregates = aggregateDao.readOverallAggregates(request.transactionType(),
                    request.from(), request.to());
        } else {
            aggregates = aggregateDao.readTransactionAggregates(request.transactionType(),
                    transactionName, request.from(), request.to());
        }
        List<StackedPoint> stackedPoints = Lists.newArrayList();
        for (Aggregate aggregate : aggregates) {
            stackedPoints.add(StackedPoint.create(aggregate));
        }

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
                addInitialUpslope(request.from(), aggregate.captureTime(), dataSeriesList,
                        otherDataSeries);
            } else {
                addGapIfNeeded(lastAggregate.captureTime(), aggregate.captureTime(),
                        dataSeriesList, otherDataSeries);
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
            addFinalDownslope(request.to(), dataSeriesList, otherDataSeries,
                    lastAggregate.captureTime());
        }
        dataSeriesList.add(otherDataSeries);

        MergedAggregate mergedAggregate = aggregateCommonService.getMergedAggregate(aggregates);
        long traceCount;
        if (transactionName == null) {
            traceCount = traceDao.readOverallCount(request.transactionType(), request.from(),
                    request.to());
        } else {
            traceCount = traceDao.readTransactionCount(request.transactionType(),
                    transactionName,
                    request.from(), request.to());
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeObjectField("mergedAggregate", mergedAggregate);
        jg.writeObjectField("traceCount", traceCount);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/performance/transaction-summaries")
    String getTransactionSummaries(String queryString) throws Exception {
        logger.debug("getTransactionSummaries(): queryString={}", queryString);
        AggregateRequestWithLimit request =
                QueryStrings.decode(queryString, AggregateRequestWithLimit.class);
        QueryResult<Summary> queryResult =
                aggregateDao.readTransactionSummaries(request.transactionType(),
                        request.from(), request.to(), request.limit());
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeFieldName("transactionSummaries");
        SummaryMarshaler.instance().marshalIterable(jg, queryResult.records());
        jg.writeBooleanField("moreAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/performance/profile")
    String getProfile(String queryString) throws Exception {
        logger.debug("getProfile(): queryString={}", queryString);
        ProfileRequest request = QueryStrings.decode(queryString, ProfileRequest.class);
        AggregateProfileNode profile = aggregateCommonService.getProfile(
                request.transactionType(), request.transactionName(), request.from(),
                request.to(), request.truncateLeafPercentage());
        return mapper.writeValueAsString(profile);
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

    private @Nullable Aggregate getNextAggregateIfMatching(PeekingIterator<Aggregate> aggregates,
            long captureTime) {
        if (!aggregates.hasNext()) {
            return null;
        }
        Aggregate aggregate = aggregates.peek();
        if (aggregate.captureTime() == captureTime) {
            // advance iterator
            aggregates.next();
            return aggregate;
        }
        return null;
    }

    private void addInitialUpslope(long requestFrom, long captureTime,
            List<DataSeries> dataSeriesList, @Nullable DataSeries otherDataSeries) {
        long millisecondsFromEdge = captureTime - requestFrom;
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring up from zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(captureTime - fixedAggregateIntervalMillis / 2, 0);
        }
        if (otherDataSeries != null) {
            otherDataSeries.add(captureTime - fixedAggregateIntervalMillis / 2, 0);
        }
    }

    private void addGapIfNeeded(long lastCaptureTime, long captureTime,
            List<DataSeries> dataSeriesList, @Nullable DataSeries otherDataSeries) {
        long millisecondsSinceLastPoint = captureTime - lastCaptureTime;
        if (millisecondsSinceLastPoint < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        // gap between points, bring down to zero and then back up from zero to show gap
        for (DataSeries dataSeries : dataSeriesList) {
            addGap(dataSeries, lastCaptureTime, captureTime);
        }
        if (otherDataSeries != null) {
            addGap(otherDataSeries, lastCaptureTime, captureTime);
        }
    }

    private void addGap(DataSeries dataSeries, long lastCaptureTime, long captureTime) {
        dataSeries.add(lastCaptureTime + fixedAggregateIntervalMillis / 2, 0);
        dataSeries.addNull();
        dataSeries.add(captureTime - fixedAggregateIntervalMillis / 2, 0);
    }

    private void addFinalDownslope(long requestCaptureTimeTo, List<DataSeries> dataSeriesList,
            @Nullable DataSeries otherDataSeries, long lastCaptureTime) {
        long millisecondsAgoFromNow = clock.currentTimeMillis() - lastCaptureTime;
        if (millisecondsAgoFromNow < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        long millisecondsFromEdge = requestCaptureTimeTo - lastCaptureTime;
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring down to zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(lastCaptureTime + fixedAggregateIntervalMillis / 2, 0);
        }
        if (otherDataSeries != null) {
            otherDataSeries.add(lastCaptureTime + fixedAggregateIntervalMillis / 2, 0);
        }
    }

    private static class StackedPoint {

        private final Aggregate aggregate;
        // stacked metric values only include time spent as a leaf node in the metric tree
        private final MutableLongMap<String> stackedMetrics;

        private static StackedPoint create(Aggregate aggregate) throws IOException {
            String metrics = aggregate.metrics();
            if (metrics == null) {
                return new StackedPoint(aggregate, new MutableLongMap<String>());
            }
            MutableLongMap<String> stackedMetrics = new MutableLongMap<String>();
            AggregateMetric syntheticRootMetric = mapper.readValue(metrics, AggregateMetric.class);
            // skip synthetic root metric
            for (AggregateMetric realRootMetric : syntheticRootMetric.getNestedMetrics()) {
                // skip real root metrics
                for (AggregateMetric topLevelMetric : realRootMetric.getNestedMetrics()) {
                    // traverse tree starting at top-level (under root) metrics
                    for (AggregateMetric metric : AggregateMetric.TRAVERSER
                            .preOrderTraversal(topLevelMetric)) {
                        long totalNestedMicros = 0;
                        for (AggregateMetric nestedMetric : metric.getNestedMetrics()) {
                            totalNestedMicros += nestedMetric.getTotalMicros();
                        }
                        stackedMetrics.add(metric.getName(),
                                metric.getTotalMicros() - totalNestedMicros);
                    }
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
    abstract static class AggregateRequest {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class AggregateRequestWithLimit {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
        abstract int limit();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class ProfileRequest {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract String transactionName();
        abstract double truncateLeafPercentage();
    }
}
