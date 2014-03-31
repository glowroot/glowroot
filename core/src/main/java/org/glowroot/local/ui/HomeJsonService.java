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
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CaseFormat;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.AtomicLongMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.Aggregate;
import org.glowroot.local.store.Aggregate.AggregateMetric;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.SortDirection;
import org.glowroot.local.store.AggregateDao.TransactionAggregateSortColumn;
import org.glowroot.local.store.OverallAggregate;
import org.glowroot.local.store.TransactionAggregate;
import org.glowroot.markers.Singleton;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to read aggregate data, bound to /backend/home.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class HomeJsonService {

    private static final Logger logger = LoggerFactory.getLogger(HomeJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateDao aggregateDao;
    private final Clock clock;
    private final long fixedAggregationIntervalMillis;

    HomeJsonService(AggregateDao aggregateDao, Clock clock, long fixedAggregationIntervalSeconds) {
        this.aggregateDao = aggregateDao;
        this.clock = clock;
        fixedAggregationIntervalMillis = fixedAggregationIntervalSeconds * 1000;
    }

    @GET("/backend/home/stacked")
    String getStacked(String content) throws IOException {
        logger.debug("getStacked(): content={}", content);
        StackedRequest request =
                ObjectMappers.readRequiredValue(mapper, content, StackedRequest.class);
        List<Aggregate> aggregates;
        String transactionName = request.getTransactionName();
        if (transactionName == null) {
            aggregates = aggregateDao.readAggregates(request.getFrom(), request.getTo());
        } else {
            aggregates = aggregateDao.readTransactionAggregates(request.getFrom(),
                    request.getTo(), transactionName);
        }
        List<StackedAggregate> stackedAggregates = Lists.newArrayList();
        for (Aggregate aggregate : aggregates) {
            stackedAggregates.add(StackedAggregate.create(aggregate));
        }
        List<String> metricNames = getTopMetricNames(stackedAggregates);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (String metricName : metricNames) {
            dataSeriesList.add(new DataSeries(metricName));
        }
        DataSeries otherDataSeries = new DataSeries(null);
        Aggregate lastAggregate = null;
        for (StackedAggregate stackedAggregate : stackedAggregates) {
            Aggregate aggregate = stackedAggregate.getAggregate();
            if (lastAggregate == null) {
                // first aggregate
                addInitialUpslope(request.getFrom(), aggregate, dataSeriesList, otherDataSeries);
            } else {
                addGap(lastAggregate, aggregate, dataSeriesList, otherDataSeries);
            }
            lastAggregate = aggregate;
            Map<String, Long> stackedMetrics = stackedAggregate.getStackedMetrics();
            long totalOtherMicros = aggregate.getTotalMicros();
            for (DataSeries dataSeries : dataSeriesList) {
                Long totalMicros = stackedMetrics.get(dataSeries.getName());
                if (totalMicros == null) {
                    dataSeries.add(aggregate.getCaptureTime(), 0);
                } else {
                    dataSeries.add(aggregate.getCaptureTime(), totalMicros / aggregate.getCount());
                    totalOtherMicros -= totalMicros;
                }
            }
            if (aggregate.getCount() == 0) {
                otherDataSeries.add(aggregate.getCaptureTime(), 0);
            } else {
                otherDataSeries.add(aggregate.getCaptureTime(),
                        totalOtherMicros / aggregate.getCount());
            }
        }
        if (lastAggregate != null) {
            addFinalDownslope(request, dataSeriesList, otherDataSeries, lastAggregate);
        }
        dataSeriesList.add(otherDataSeries);
        return mapper.writeValueAsString(dataSeriesList);
    }

    @GET("/backend/home/aggregates")
    String getAggregates(String content) throws IOException {
        logger.debug("getAggregates(): content={}", content);
        AggregatesRequest request =
                ObjectMappers.readRequiredValue(mapper, content, AggregatesRequest.class);
        OverallAggregate overallAggregate =
                aggregateDao.readOverallAggregate(request.getFrom(), request.getTo());
        String sortAttribute =
                CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, request.getSortAttribute());
        TransactionAggregateSortColumn sortColumn =
                TransactionAggregateSortColumn.valueOf(sortAttribute);
        List<TransactionAggregate> transactionAggregates = aggregateDao.readTransactionAggregates(
                request.getFrom(), request.getTo(), sortColumn, request.getSortDirection(),
                request.getTransactionAggregatesLimit());
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("overallAggregate", overallAggregate);
        jg.writeObjectField("transactionAggregates", transactionAggregates);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    // calculate top 5 metrics
    private List<String> getTopMetricNames(List<StackedAggregate> stackedAggregates) {
        AtomicLongMap<String> totalMicros = AtomicLongMap.create();
        for (StackedAggregate stackedAggregate : stackedAggregates) {
            totalMicros.putAll(stackedAggregate.getStackedMetrics());
        }
        Ordering<Entry<String, Long>> valueOrdering = Ordering.natural().onResultOf(
                new Function<Entry<String, Long>, Long>() {
                    @Override
                    public Long apply(@Nullable Entry<String, Long> entry) {
                        checkNotNull(entry);
                        return entry.getValue();
                    }
                });
        final int topX = 5;
        List<String> metricNames = Lists.newArrayList();
        for (Entry<String, Long> entry : valueOrdering
                .greatestOf(totalMicros.asMap().entrySet(), topX)) {
            metricNames.add(entry.getKey());
        }
        return metricNames;
    }

    private void addInitialUpslope(long requestFrom, Aggregate aggregate,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries) {
        long millisecondsFromEdge = aggregate.getCaptureTime() - requestFrom;
        if (millisecondsFromEdge < fixedAggregationIntervalMillis / 2) {
            return;
        }
        // bring up from zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(aggregate.getCaptureTime() - fixedAggregationIntervalMillis / 2, 0);
        }
        otherDataSeries.add(aggregate.getCaptureTime() - fixedAggregationIntervalMillis / 2, 0);
    }

    private void addGap(Aggregate lastAggregate, Aggregate aggregate,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries) {
        long millisecondsSinceLastAggregate =
                aggregate.getCaptureTime() - lastAggregate.getCaptureTime();
        if (millisecondsSinceLastAggregate < fixedAggregationIntervalMillis * 1.5) {
            return;
        }
        // gap between points, bring down to zero and then back up from zero to show gap
        for (DataSeries dataSeries : dataSeriesList) {
            addGap(dataSeries, lastAggregate, aggregate);
        }
        addGap(otherDataSeries, lastAggregate, aggregate);
    }

    private void addGap(DataSeries dataSeries, Aggregate lastAggregate, Aggregate aggregate) {
        dataSeries.add(lastAggregate.getCaptureTime()
                + fixedAggregationIntervalMillis / 2, 0);
        dataSeries.addNull();
        dataSeries.add(aggregate.getCaptureTime()
                - fixedAggregationIntervalMillis / 2, 0);
    }

    private void addFinalDownslope(StackedRequest request, List<DataSeries> dataSeriesList,
            DataSeries otherDataSeries, Aggregate lastAggregate) {
        long millisecondsAgoFromNow = clock.currentTimeMillis() - lastAggregate.getCaptureTime();
        if (millisecondsAgoFromNow < fixedAggregationIntervalMillis * 1.5) {
            return;
        }
        long millisecondsFromEdge = request.getTo() - lastAggregate.getCaptureTime();
        if (millisecondsFromEdge < fixedAggregationIntervalMillis / 2) {
            return;
        }
        // bring down to zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(lastAggregate.getCaptureTime()
                    + fixedAggregationIntervalMillis / 2, 0);
        }
        otherDataSeries.add(lastAggregate.getCaptureTime()
                + fixedAggregationIntervalMillis / 2, 0);
    }

    private static class StackedRequest {

        private final long from;
        private final long to;
        @Nullable
        private final String transactionName;

        @JsonCreator
        StackedRequest(@JsonProperty("from") @Nullable Long from,
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

    private static class AggregatesRequest {

        private final long from;
        private final long to;
        private final String sortAttribute;
        private final SortDirection sortDirection;
        private final int transactionAggregatesLimit;

        @JsonCreator
        AggregatesRequest(
                @JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("sortAttribute") @Nullable String sortAttribute,
                @JsonProperty("sortDirection") @Nullable SortDirection sortDirection,
                @JsonProperty("transactionAggregatesLimit") @Nullable Integer transactionAggregatesLimit)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            checkRequiredProperty(sortAttribute, "sortAttribute");
            checkRequiredProperty(sortDirection, "sortDirection");
            checkRequiredProperty(transactionAggregatesLimit, "transactionAggregatesLimit");
            this.from = from;
            this.to = to;
            this.sortAttribute = sortAttribute;
            this.sortDirection = sortDirection;
            this.transactionAggregatesLimit = transactionAggregatesLimit;
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

        private int getTransactionAggregatesLimit() {
            return transactionAggregatesLimit;
        }
    }

    private static class StackedAggregate {

        private final Aggregate aggregate;
        // flattened metric values only include time spent as a leaf node in the metric tree
        private final Map<String, Long> stackedMetrics;

        private static StackedAggregate create(Aggregate aggregate) {
            AtomicLongMap<String> stackedMetrics = AtomicLongMap.create();
            // skip synthetic root metric
            for (AggregateMetric rootMetric : aggregate.getSyntheticRootAggregateMetric()
                    .getNestedMetrics()) {
                // skip root metrics
                for (AggregateMetric topLevelMetric : rootMetric.getNestedMetrics()) {
                    // traverse tree starting at top-level (under root) metrics
                    for (AggregateMetric metric : AggregateMetric.TRAVERSER
                            .preOrderTraversal(topLevelMetric)) {
                        long totalNestedMicros = 0;
                        for (AggregateMetric nestedMetric : metric.getNestedMetrics()) {
                            totalNestedMicros += nestedMetric.getTotalMicros();
                        }
                        stackedMetrics.addAndGet(metric.getName(),
                                metric.getTotalMicros() - totalNestedMicros);
                    }
                }
            }
            return new StackedAggregate(aggregate, stackedMetrics.asMap());
        }

        private StackedAggregate(Aggregate aggregate, Map<String, Long> stackedMetrics) {
            this.aggregate = aggregate;
            this.stackedMetrics = stackedMetrics;
        }

        private Aggregate getAggregate() {
            return aggregate;
        }

        private Map<String, Long> getStackedMetrics() {
            return stackedMetrics;
        }
    }
}
