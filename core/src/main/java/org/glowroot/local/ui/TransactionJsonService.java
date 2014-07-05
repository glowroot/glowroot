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

import org.glowroot.collector.TransactionPoint;
import org.glowroot.common.Clock;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.Overall;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.TransactionPointDao;
import org.glowroot.local.store.TransactionSummary;
import org.glowroot.local.store.TransactionSummaryQuery;
import org.glowroot.local.ui.TransactionCommonService.TransactionHeader;
import org.glowroot.markers.Singleton;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to read transaction point data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class TransactionJsonService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TransactionCommonService transactionCommonService;
    private final TransactionPointDao transactionPointDao;
    private final Clock clock;
    private final long fixedTransactionPointIntervalMillis;

    TransactionJsonService(TransactionCommonService transactionCommonService,
            TransactionPointDao transactionPointDao, Clock clock,
            long fixedTransactionPointIntervalSeconds) {
        this.transactionCommonService = transactionCommonService;
        this.transactionPointDao = transactionPointDao;
        this.clock = clock;
        fixedTransactionPointIntervalMillis = fixedTransactionPointIntervalSeconds * 1000;
    }

    @GET("/backend/transaction/stacked")
    String getStacked(String content) throws IOException {
        logger.debug("getStacked(): content={}", content);
        RequestWithTransactionName request =
                ObjectMappers.readRequiredValue(mapper, content, RequestWithTransactionName.class);
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
        List<String> traceMetrics = getTopTraceMetrics(stackedPoints);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (String traceMetric : traceMetrics) {
            dataSeriesList.add(new DataSeries(traceMetric));
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
                            totalMicros.longValue() / (double) transactionPoint.getCount());
                    totalOtherMicros -= totalMicros.longValue();
                }
            }
            if (transactionPoint.getCount() == 0) {
                otherDataSeries.add(transactionPoint.getCaptureTime(), 0);
            } else {
                otherDataSeries.add(transactionPoint.getCaptureTime(),
                        totalOtherMicros / (double) transactionPoint.getCount());
            }
        }
        if (lastTransactionPoint != null) {
            addFinalDownslope(request, dataSeriesList, otherDataSeries, lastTransactionPoint);
        }
        dataSeriesList.add(otherDataSeries);
        return mapper.writeValueAsString(dataSeriesList);
    }

    @GET("/backend/transaction/summaries")
    String getSummaries(String content) throws IOException {
        logger.debug("getSummaries(): content={}", content);
        TransactionSummaryQuery query =
                ObjectMappers.readRequiredValue(mapper, content, TransactionSummaryQuery.class);
        Overall overall = transactionPointDao.readOverall(query.getTransactionType(),
                query.getFrom(), query.getTo());
        QueryResult<TransactionSummary> queryResult =
                transactionPointDao.readTransactionSummaries(query);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("overall", overall);
        jg.writeObjectField("transactions", queryResult.getRecords());
        jg.writeBooleanField("moreAvailable", queryResult.isMoreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/header")
    String getHeader(String content) throws IOException {
        logger.debug("getHeader(): content={}", content);
        RequestWithTransactionName request =
                ObjectMappers.readRequiredValue(mapper, content, RequestWithTransactionName.class);
        TransactionHeader transactionHeader = transactionCommonService.getTransactionHeader(
                request.getTransactionName(), request.getFrom(), request.getTo());
        return mapper.writeValueAsString(transactionHeader);
    }

    @GET("/backend/transaction/profile")
    String getProfile(String content) throws IOException {
        logger.debug("getProfile(): content={}", content);
        ProfileRequest request =
                ObjectMappers.readRequiredValue(mapper, content, ProfileRequest.class);
        TransactionProfileNode profile = transactionCommonService.getProfile(
                request.getTransactionName(), request.getFrom(), request.getTo(),
                request.getTruncateLeafPercentage());
        return mapper.writeValueAsString(profile);
    }

    // calculate top 5 trace metrics
    private List<String> getTopTraceMetrics(List<StackedPoint> stackedPoints) {
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
        List<String> traceMetrics = Lists.newArrayList();
        for (Entry<String, MutableLong> entry : valueOrdering
                .greatestOf(totalMicros.entrySet(), topX)) {
            traceMetrics.add(entry.getKey());
        }
        return traceMetrics;
    }

    private void addInitialUpslope(long requestFrom, TransactionPoint transactionPoint,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries) {
        long millisecondsFromEdge = transactionPoint.getCaptureTime() - requestFrom;
        if (millisecondsFromEdge < fixedTransactionPointIntervalMillis / 2) {
            return;
        }
        // bring up from zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(transactionPoint.getCaptureTime()
                    - fixedTransactionPointIntervalMillis / 2, 0);
        }
        otherDataSeries.add(transactionPoint.getCaptureTime()
                - fixedTransactionPointIntervalMillis / 2, 0);
    }

    private void addGap(TransactionPoint lastTransactionPoint, TransactionPoint transactionPoint,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries) {
        long millisecondsSinceLastPoint =
                transactionPoint.getCaptureTime() - lastTransactionPoint.getCaptureTime();
        if (millisecondsSinceLastPoint < fixedTransactionPointIntervalMillis * 1.5) {
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
                + fixedTransactionPointIntervalMillis / 2, 0);
        dataSeries.addNull();
        dataSeries.add(transactionPoint.getCaptureTime()
                - fixedTransactionPointIntervalMillis / 2, 0);
    }

    private void addFinalDownslope(RequestWithTransactionName request,
            List<DataSeries> dataSeriesList, DataSeries otherDataSeries,
            TransactionPoint lastTransactionPoint) {
        long millisecondsAgoFromNow =
                clock.currentTimeMillis() - lastTransactionPoint.getCaptureTime();
        if (millisecondsAgoFromNow < fixedTransactionPointIntervalMillis * 1.5) {
            return;
        }
        long millisecondsFromEdge = request.getTo() - lastTransactionPoint.getCaptureTime();
        if (millisecondsFromEdge < fixedTransactionPointIntervalMillis / 2) {
            return;
        }
        // bring down to zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(lastTransactionPoint.getCaptureTime()
                    + fixedTransactionPointIntervalMillis / 2, 0);
        }
        otherDataSeries.add(lastTransactionPoint.getCaptureTime()
                + fixedTransactionPointIntervalMillis / 2, 0);
    }

    private static class RequestWithTransactionName {

        private final long from;
        private final long to;
        @Nullable
        private final String transactionName;

        @JsonCreator
        RequestWithTransactionName(@JsonProperty("from") @Nullable Long from,
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

    private static class ProfileRequest {

        private final long from;
        private final long to;
        private final String transactionName;
        private final double truncateLeafPercentage;

        @JsonCreator
        ProfileRequest(@JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("transactionName") @Nullable String transactionName,
                @JsonProperty("truncateLeafPercentage") double truncateLeafPercentage)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            checkRequiredProperty(transactionName, "transactionName");
            this.from = from;
            this.to = to;
            this.transactionName = transactionName;
            this.truncateLeafPercentage = truncateLeafPercentage;
        }

        private long getFrom() {
            return from;
        }

        private long getTo() {
            return to;
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

    private static class StackedPoint {

        private final TransactionPoint transactionPoint;
        // stacked metric values only include time spent as a leaf node in the trace metric tree
        private final MutableLongMap<String> stackedMetrics;

        private static StackedPoint create(TransactionPoint transactionPoint)
                throws IOException {
            String traceMetrics = transactionPoint.getTraceMetrics();
            if (traceMetrics == null) {
                return new StackedPoint(transactionPoint, new MutableLongMap<String>());
            }
            MutableLongMap<String> stackedTraceMetrics = new MutableLongMap<String>();
            SimpleTraceMetric syntheticRoot =
                    mapper.readValue(traceMetrics, SimpleTraceMetric.class);
            // skip synthetic root trace metric
            for (SimpleTraceMetric realRoot : syntheticRoot.getNestedTraceMetrics()) {
                // skip real root trace metrics
                for (SimpleTraceMetric topLevelTraceMetric : realRoot.getNestedTraceMetrics()) {
                    // traverse tree starting at top-level (under root) trace metrics
                    for (SimpleTraceMetric traceMetric : SimpleTraceMetric.TRAVERSER
                            .preOrderTraversal(topLevelTraceMetric)) {
                        long totalNestedMicros = 0;
                        for (SimpleTraceMetric nestedTraceMetric : traceMetric
                                .getNestedTraceMetrics()) {
                            totalNestedMicros += nestedTraceMetric.getTotalMicros();
                        }
                        stackedTraceMetrics.add(traceMetric.getName(),
                                traceMetric.getTotalMicros() - totalNestedMicros);
                    }
                }
            }
            return new StackedPoint(transactionPoint, stackedTraceMetrics);
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
