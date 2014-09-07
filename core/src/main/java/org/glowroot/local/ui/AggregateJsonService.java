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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Aggregate;
import org.glowroot.common.Clock;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.Summary;
import org.glowroot.local.store.SummaryQuery;
import org.glowroot.local.ui.AggregateCommonService.MergedAggregate;
import org.glowroot.markers.Singleton;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to read aggregate data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class AggregateJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AggregateJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateCommonService aggregateCommonService;
    private final AggregateDao aggregateDao;
    private final Clock clock;
    private final long fixedAggregateIntervalMillis;

    AggregateJsonService(AggregateCommonService aggregateCommonService, AggregateDao aggregateDao,
            Clock clock, long fixedAggregateIntervalSeconds) {
        this.aggregateCommonService = aggregateCommonService;
        this.aggregateDao = aggregateDao;
        this.clock = clock;
        fixedAggregateIntervalMillis = fixedAggregateIntervalSeconds * 1000;
    }

    @GET("/backend/aggregate/stacked")
    String getStacked(String content) throws IOException {
        logger.debug("getStacked(): content={}", content);
        RequestWithTransactionName request =
                ObjectMappers.readRequiredValue(mapper, content, RequestWithTransactionName.class);
        List<Aggregate> aggregates;
        String transactionName = request.getTransactionName();
        if (transactionName == null) {
            aggregates = aggregateDao.readOverallAggregates(request.getTransactionType(),
                    request.getFrom(), request.getTo());
        } else {
            aggregates = aggregateDao.readTransactionAggregates(request.getTransactionType(),
                    transactionName, request.getFrom(), request.getTo());
        }
        List<StackedPoint> stackedPoints = Lists.newArrayList();
        for (Aggregate aggregate : aggregates) {
            stackedPoints.add(StackedPoint.create(aggregate));
        }
        List<String> metricNames = getTopMetricNames(stackedPoints);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (String metricName : metricNames) {
            dataSeriesList.add(new DataSeries(metricName));
        }
        DataSeries otherDataSeries = new DataSeries(null);
        Aggregate lastAggregate = null;
        for (StackedPoint stackedPoint : stackedPoints) {
            Aggregate aggregate = stackedPoint.getAggregate();
            if (lastAggregate == null) {
                // first aggregate
                addInitialUpslope(request.getFrom(), aggregate, dataSeriesList,
                        otherDataSeries);
            } else {
                addGap(lastAggregate, aggregate, dataSeriesList, otherDataSeries);
            }
            lastAggregate = aggregate;
            MutableLongMap<String> stackedMetrics = stackedPoint.getStackedMetrics();
            long totalOtherMicros = aggregate.getTotalMicros();
            for (DataSeries dataSeries : dataSeriesList) {
                MutableLong totalMicros = stackedMetrics.get(dataSeries.getMetricName());
                if (totalMicros == null) {
                    dataSeries.add(aggregate.getCaptureTime(), 0);
                } else {
                    dataSeries.add(aggregate.getCaptureTime(),
                            totalMicros.longValue() / (double) aggregate.getCount());
                    totalOtherMicros -= totalMicros.longValue();
                }
            }
            if (aggregate.getCount() == 0) {
                otherDataSeries.add(aggregate.getCaptureTime(), 0);
            } else {
                otherDataSeries.add(aggregate.getCaptureTime(),
                        totalOtherMicros / (double) aggregate.getCount());
            }
        }
        if (lastAggregate != null) {
            addFinalDownslope(request, dataSeriesList, otherDataSeries, lastAggregate);
        }
        dataSeriesList.add(otherDataSeries);
        return mapper.writeValueAsString(dataSeriesList);
    }

    @GET("/backend/aggregate/summaries")
    String getSummaries(String content) throws IOException {
        logger.debug("getSummaries(): content={}", content);
        SummaryQuery query = ObjectMappers.readRequiredValue(mapper, content, SummaryQuery.class);
        Summary overallSummary = aggregateDao.readOverallSummary(query.getTransactionType(),
                query.getFrom(), query.getTo());
        QueryResult<Summary> queryResult = aggregateDao.readTransactionSummaries(query);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("overallSummary", overallSummary);
        jg.writeObjectField("transactionSummaries", queryResult.getRecords());
        jg.writeBooleanField("moreAvailable", queryResult.isMoreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/aggregate/header")
    String getHeader(String content) throws IOException {
        logger.debug("getHeader(): content={}", content);
        RequestWithTransactionName request =
                ObjectMappers.readRequiredValue(mapper, content, RequestWithTransactionName.class);
        MergedAggregate mergedAggregate = aggregateCommonService.getMergedAggregate(
                request.getTransactionType(), request.getTransactionName(), request.getFrom(),
                request.getTo());
        return mapper.writeValueAsString(mergedAggregate);
    }

    @GET("/backend/aggregate/profile")
    String getProfile(String content) throws IOException {
        logger.debug("getProfile(): content={}", content);
        ProfileRequest request =
                ObjectMappers.readRequiredValue(mapper, content, ProfileRequest.class);
        AggregateProfileNode profile = aggregateCommonService.getProfile(
                request.getTransactionType(), request.getTransactionName(), request.getFrom(),
                request.getTo(), request.getTruncateLeafPercentage());
        return mapper.writeValueAsString(profile);
    }

    // calculate top 5 metrics
    private List<String> getTopMetricNames(List<StackedPoint> stackedPoints) {
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
        final int topX = 5;
        List<String> metricNames = Lists.newArrayList();
        List<Entry<String, MutableLong>> topMetricTotals =
                valueOrdering.greatestOf(metricTotals.entrySet(), topX);
        for (Entry<String, MutableLong> entry : topMetricTotals) {
            metricNames.add(entry.getKey());
        }
        return metricNames;
    }

    private void addInitialUpslope(long requestFrom, Aggregate aggregate,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries) {
        long millisecondsFromEdge = aggregate.getCaptureTime() - requestFrom;
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring up from zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(aggregate.getCaptureTime() - fixedAggregateIntervalMillis / 2, 0);
        }
        otherDataSeries.add(aggregate.getCaptureTime() - fixedAggregateIntervalMillis / 2, 0);
    }

    private void addGap(Aggregate lastAggregate, Aggregate aggregate,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries) {
        long millisecondsSinceLastPoint =
                aggregate.getCaptureTime() - lastAggregate.getCaptureTime();
        if (millisecondsSinceLastPoint < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        // gap between points, bring down to zero and then back up from zero to show gap
        for (DataSeries dataSeries : dataSeriesList) {
            addGap(dataSeries, lastAggregate, aggregate);
        }
        addGap(otherDataSeries, lastAggregate, aggregate);
    }

    private void addGap(DataSeries dataSeries, Aggregate lastAggregate, Aggregate aggregate) {
        dataSeries.add(lastAggregate.getCaptureTime() + fixedAggregateIntervalMillis / 2, 0);
        dataSeries.addNull();
        dataSeries.add(aggregate.getCaptureTime() - fixedAggregateIntervalMillis / 2, 0);
    }

    private void addFinalDownslope(RequestWithTransactionName request,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries, Aggregate lastAggregate) {
        long millisecondsAgoFromNow = clock.currentTimeMillis() - lastAggregate.getCaptureTime();
        if (millisecondsAgoFromNow < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        long millisecondsFromEdge = request.getTo() - lastAggregate.getCaptureTime();
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring down to zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(lastAggregate.getCaptureTime() + fixedAggregateIntervalMillis / 2, 0);
        }
        otherDataSeries.add(lastAggregate.getCaptureTime() + fixedAggregateIntervalMillis / 2, 0);
    }

    private static class RequestWithTransactionName {

        private final long from;
        private final long to;
        private final String transactionType;
        @Nullable
        private final String transactionName;

        @JsonCreator
        RequestWithTransactionName(@JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("transactionType") @Nullable String transactionType,
                @JsonProperty("transactionName") @Nullable String transactionName)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            checkRequiredProperty(transactionType, "transactionType");
            this.from = from;
            this.to = to;
            this.transactionType = transactionType;
            this.transactionName = transactionName;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }

        private String getTransactionType() {
            return transactionType;
        }

        @Nullable
        private String getTransactionName() {
            return transactionName;
        }
    }

    private static class ProfileRequest {

        private final long from;
        private final long to;
        private final String transactionType;
        private final String transactionName;
        private final double truncateLeafPercentage;

        @JsonCreator
        ProfileRequest(@JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("transactionType") @Nullable String transactionType,
                @JsonProperty("transactionName") @Nullable String transactionName,
                @JsonProperty("truncateLeafPercentage") double truncateLeafPercentage)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            checkRequiredProperty(transactionType, "transactionType");
            checkRequiredProperty(transactionName, "transactionName");
            this.from = from;
            this.to = to;
            this.transactionType = transactionType;
            this.transactionName = transactionName;
            this.truncateLeafPercentage = truncateLeafPercentage;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
        }

        private String getTransactionType() {
            return transactionType;
        }

        private String getTransactionName() {
            return transactionName;
        }

        private double getTruncateLeafPercentage() {
            return truncateLeafPercentage;
        }
    }

    // this is used for stacked response
    private static class DataSeries {

        // null is used for 'Other' data series
        @JsonProperty
        @Nullable
        private final String metricName;
        @JsonProperty
        private final List<Number/*@Nullable*/[]> data = Lists.newArrayList();

        private DataSeries(@Nullable String metricName) {
            this.metricName = metricName;
        }

        @Nullable
        private String getMetricName() {
            return metricName;
        }

        private void add(long captureTime, double averageMicros) {
            // convert microseconds to seconds
            data.add(new Number[] {captureTime, averageMicros / 1000000});
        }

        private void addNull() {
            data.add(null);
        }
    }

    private static class StackedPoint {

        private final Aggregate aggregate;
        // stacked metric values only include time spent as a leaf node in the metric tree
        private final MutableLongMap<String> stackedMetrics;

        private static StackedPoint create(Aggregate aggregate) throws IOException {
            String metrics = aggregate.getMetrics();
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
}
