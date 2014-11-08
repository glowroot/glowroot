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
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.PeekingIterator;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.ErrorCount;
import org.glowroot.local.store.ErrorMessageCount;
import org.glowroot.local.store.ErrorMessageQuery;
import org.glowroot.local.store.ErrorPoint;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.TraceDao;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

@JsonService
class ErrorJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ErrorJsonService.class);
    private static final ObjectMapper mapper;

    static {
        mapper = ObjectMappers.create();
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final Clock clock;
    private final long fixedAggregateIntervalMillis;

    ErrorJsonService(AggregateDao aggregateDao, TraceDao traceDao, Clock clock,
            long fixedAggregateIntervalSeconds) {
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.clock = clock;
        fixedAggregateIntervalMillis = fixedAggregateIntervalSeconds * 1000;
    }

    @GET("/backend/error/transactions")
    String getTransactions(String content) throws IOException, SQLException {
        logger.debug("getTransactions(): content={}", content);
        ErrorRequestWithLimit request =
                ObjectMappers.readRequiredValue(mapper, content, ErrorRequestWithLimit.class);

        ErrorCount overallErrorCount =
                aggregateDao.readOverallErrorCount(request.getFrom(), request.getTo());
        QueryResult<ErrorCount> queryResult = aggregateDao.readTransactionErrorCounts(
                request.getFrom(), request.getTo(), request.getLimit());

        ImmutableList<ErrorPoint> overallErrorPoints =
                aggregateDao.readOverallErrorPoints(request.getFrom(), request.getTo());

        final int topX = 5;
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        List<PeekingIterator<ErrorPoint>> transactionErrorPointsList = Lists.newArrayList();
        for (int i = 0; i < Math.min(queryResult.getRecords().size(), topX); i++) {
            ErrorCount errorCount = queryResult.getRecords().get(i);
            String transactionType = errorCount.getTransactionType();
            String transactionName = errorCount.getTransactionName();
            checkNotNull(transactionType);
            checkNotNull(transactionName);
            dataSeriesList.add(new DataSeries(transactionName));
            transactionErrorPointsList.add(Iterators.peekingIterator(
                    aggregateDao.readTransactionErrorPoints(transactionType, transactionName,
                            request.getFrom(), request.getTo()).iterator()));
        }

        DataSeries otherDataSeries = null;
        if (queryResult.getRecords().size() > topX) {
            otherDataSeries = new DataSeries(null);
        }

        ErrorPoint lastOverallErrorPoint = null;
        for (ErrorPoint overallErrorPoint : overallErrorPoints) {
            if (lastOverallErrorPoint == null) {
                // first aggregate
                addInitialUpslope(request.getFrom(), overallErrorPoint.getCaptureTime(),
                        dataSeriesList, otherDataSeries);
            } else {
                addGapIfNeeded(lastOverallErrorPoint.getCaptureTime(),
                        overallErrorPoint.getCaptureTime(), dataSeriesList, otherDataSeries);
            }
            lastOverallErrorPoint = overallErrorPoint;

            long totalOtherErrorCount = overallErrorPoint.getErrorCount();
            for (int i = 0; i < dataSeriesList.size(); i++) {
                PeekingIterator<ErrorPoint> transactionErrorPoints =
                        transactionErrorPointsList.get(i);
                ErrorPoint transactionErrorPoint = getNextErrorPointIfMatching(
                        transactionErrorPoints, overallErrorPoint.getCaptureTime());
                DataSeries dataSeries = dataSeriesList.get(i);
                if (transactionErrorPoint == null) {
                    dataSeries.add(overallErrorPoint.getCaptureTime(), 0);
                } else {
                    dataSeries.add(overallErrorPoint.getCaptureTime(),
                            100 * transactionErrorPoint.getErrorCount()
                                    / (double) overallErrorPoint.getTransactionCount());
                    totalOtherErrorCount -= transactionErrorPoint.getErrorCount();
                }
            }
            if (otherDataSeries != null) {
                otherDataSeries.add(overallErrorPoint.getCaptureTime(), 100 * totalOtherErrorCount
                        / (double) overallErrorPoint.getTransactionCount());
            }
        }
        if (lastOverallErrorPoint != null) {
            addFinalDownslope(request.getTo(), dataSeriesList, otherDataSeries,
                    lastOverallErrorPoint.getCaptureTime());
        }
        if (otherDataSeries != null) {
            dataSeriesList.add(otherDataSeries);
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeObjectField("overallSummary", overallErrorCount);
        jg.writeObjectField("transactionSummaries", queryResult.getRecords());
        jg.writeBooleanField("moreAvailable", queryResult.isMoreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/error/messages")
    String getErrorMessages(String content) throws IOException, SQLException {
        logger.debug("getErrorMessages(): content={}", content);
        ObjectMapper mapper = ObjectMappers.create();
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        ErrorMessageQuery request =
                ObjectMappers.readRequiredValue(mapper, content, ErrorMessageQuery.class);
        QueryResult<ErrorMessageCount> queryResult = traceDao.readErrorMessageCounts(request);
        String transactionType = request.getTransactionType();
        String transactionName = request.getTransactionName();
        List<ErrorPoint> unfilteredErrorPoints;
        if (!Strings.isNullOrEmpty(transactionType) && !Strings.isNullOrEmpty(transactionName)) {
            unfilteredErrorPoints = aggregateDao.readTransactionErrorPoints(transactionType,
                    transactionName, request.getFrom(), request.getTo());
        } else if (Strings.isNullOrEmpty(transactionType)
                && Strings.isNullOrEmpty(transactionName)) {
            unfilteredErrorPoints =
                    aggregateDao.readOverallErrorPoints(request.getFrom(), request.getTo());
        } else {
            throw new IllegalArgumentException("TransactionType and TransactionName must both be"
                    + "empty or must both be non-empty");
        }
        DataSeries dataSeries = new DataSeries(null);
        Map<Long, Long[]> dataSeriesExtra = Maps.newHashMap();
        if (request.getIncludes().isEmpty() && request.getExcludes().isEmpty()) {
            nameMe(request, unfilteredErrorPoints, dataSeries, dataSeriesExtra);
        } else {
            Map<Long, Long> transactionCountMap = Maps.newHashMap();
            for (ErrorPoint unfilteredErrorPoint : unfilteredErrorPoints) {
                transactionCountMap.put(unfilteredErrorPoint.getCaptureTime(),
                        unfilteredErrorPoint.getTransactionCount());
            }
            ImmutableList<ErrorPoint> errorPoints =
                    traceDao.readErrorPoints(request, fixedAggregateIntervalMillis);
            for (ErrorPoint errorPoint : errorPoints) {
                Long transactionCount = transactionCountMap.get(errorPoint.getCaptureTime());
                if (transactionCount != null) {
                    errorPoint.setTransactionCount(transactionCount);
                }
            }
            nameMe(request, errorPoints, dataSeries, dataSeriesExtra);
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeries);
        jg.writeObjectField("dataSeriesExtra", dataSeriesExtra);
        jg.writeObjectField("errorMessages", queryResult.getRecords());
        jg.writeBooleanField("moreAvailable", queryResult.isMoreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private void nameMe(ErrorMessageQuery request, List<ErrorPoint> errorPoints,
            DataSeries dataSeries, Map<Long, Long[]> dataSeriesExtra) {
        ErrorPoint lastErrorPoint = null;
        for (ErrorPoint errorPoint : errorPoints) {
            if (lastErrorPoint == null) {
                // first aggregate
                addInitialUpslope(request.getFrom(), errorPoint.getCaptureTime(), dataSeries);
            } else {
                addGapIfNeeded(lastErrorPoint.getCaptureTime(), errorPoint.getCaptureTime(),
                        dataSeries);
            }
            lastErrorPoint = errorPoint;
            long transactionCount = errorPoint.getTransactionCount();
            if (transactionCount == -1) {
                // make unknown really big?
                dataSeries.add(errorPoint.getCaptureTime(), 100);
            } else {
                dataSeries.add(errorPoint.getCaptureTime(),
                        100 * errorPoint.getErrorCount() / (double) transactionCount);
            }
            dataSeriesExtra.put(errorPoint.getCaptureTime(),
                    new Long[] {errorPoint.getErrorCount(), transactionCount});
        }
        if (lastErrorPoint != null) {
            addFinalDownslope(request.getTo(), dataSeries, lastErrorPoint.getCaptureTime());
        }
    }

    @GET("/backend/error/transaction-summaries")
    String getTransactionSummaries(String content) throws IOException, SQLException {
        logger.debug("getTransactionSummaries(): content={}", content);
        ErrorRequestWithLimit request =
                ObjectMappers.readRequiredValue(mapper, content, ErrorRequestWithLimit.class);
        QueryResult<ErrorCount> queryResult = aggregateDao.readTransactionErrorCounts(
                request.getFrom(), request.getTo(), request.getLimit());
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("transactionSummaries", queryResult.getRecords());
        jg.writeBooleanField("moreAvailable", queryResult.isMoreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    // TODO consolidate copy-pasted code below with AggregateJsonService

    @Nullable
    private ErrorPoint getNextErrorPointIfMatching(PeekingIterator<ErrorPoint> errorPoints,
            long captureTime) {
        if (!errorPoints.hasNext()) {
            return null;
        }
        ErrorPoint errorPoint = errorPoints.peek();
        if (errorPoint.getCaptureTime() == captureTime) {
            // advance iterator
            errorPoints.next();
            return errorPoint;
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

    private void addInitialUpslope(long requestFrom, long captureTime, DataSeries dataSeries) {
        long millisecondsFromEdge = captureTime - requestFrom;
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring up from zero
        dataSeries.add(captureTime - fixedAggregateIntervalMillis / 2, 0);
    }

    private void addGapIfNeeded(long lastCaptureTime, long captureTime, DataSeries dataSeries) {
        long millisecondsSinceLastPoint = captureTime - lastCaptureTime;
        if (millisecondsSinceLastPoint < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        // gap between points, bring down to zero and then back up from zero to show gap
        addGap(dataSeries, lastCaptureTime, captureTime);
    }

    private void addFinalDownslope(long requestCaptureTimeTo, DataSeries dataSeries,
            long lastCaptureTime) {
        long millisecondsAgoFromNow = clock.currentTimeMillis() - lastCaptureTime;
        if (millisecondsAgoFromNow < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        long millisecondsFromEdge = requestCaptureTimeTo - lastCaptureTime;
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring down to zero
        dataSeries.add(lastCaptureTime + fixedAggregateIntervalMillis / 2, 0);
    }

    private static class ErrorRequest {

        private final long from;
        private final long to;
        @Nullable
        private final String transactionType;
        @Nullable
        private final String transactionName;

        @JsonCreator
        ErrorRequest(@JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("transactionType") @Nullable String transactionType,
                @JsonProperty("transactionName") @Nullable String transactionName)
                throws JsonMappingException {
            checkRequiredProperty(from, "from");
            checkRequiredProperty(to, "to");
            this.from = from;
            this.to = to;
            this.transactionType = transactionType;
            this.transactionName = transactionName;
        }

        long getFrom() {
            return from;
        }

        long getTo() {
            return to;
        }

        @Nullable
        String getTransactionType() {
            return transactionType;
        }

        @Nullable
        private String getTransactionName() {
            return transactionName;
        }
    }

    private static class ErrorRequestWithLimit extends ErrorRequest {

        private final int limit;

        @JsonCreator
        ErrorRequestWithLimit(@JsonProperty("from") @Nullable Long from,
                @JsonProperty("to") @Nullable Long to,
                @JsonProperty("transactionType") @Nullable String transactionType,
                @JsonProperty("transactionName") @Nullable String transactionName,
                @JsonProperty("limit") @Nullable Integer limit)
                throws JsonMappingException {
            super(from, to, transactionType, transactionName);
            checkRequiredProperty(limit, "limit");
            this.limit = limit;
        }

        private int getLimit() {
            return limit;
        }
    }
}
