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
package org.glowroot.ui;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableErrorPoint;
import org.glowroot.common.live.LiveAggregateRepository.ErrorPoint;
import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.AggregateRepository.ErrorSummaryQuery;
import org.glowroot.storage.repo.ImmutableErrorMessageQuery;
import org.glowroot.storage.repo.ImmutableErrorSummaryQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TraceRepository.ErrorMessageCount;
import org.glowroot.storage.repo.TraceRepository.ErrorMessageQuery;
import org.glowroot.storage.repo.TraceRepository.TraceErrorPoint;

@JsonService
class ErrorJsonService {

    private static final ObjectMapper mapper;

    static {
        mapper = ObjectMappers.create();
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    private final ErrorCommonService errorCommonService;
    private final TraceRepository traceRepository;
    private final AggregateRepository aggregateRepository;
    private final Clock clock;

    ErrorJsonService(ErrorCommonService errorCommonService, TraceRepository traceRepository,
            AggregateRepository aggregateRepository, Clock clock) {
        this.errorCommonService = errorCommonService;
        this.traceRepository = traceRepository;
        this.aggregateRepository = aggregateRepository;
        this.clock = clock;
    }

    @GET("/backend/error/messages")
    String getData(String queryString) throws Exception {
        ErrorMessageRequest request = QueryStrings.decode(queryString, ErrorMessageRequest.class);

        ErrorMessageQuery query = ImmutableErrorMessageQuery.builder()
                .serverRollup(request.serverRollup())
                .transactionType(request.transactionType())
                .transactionName(request.transactionName())
                .from(request.from())
                .to(request.to())
                .addAllIncludes(request.include())
                .addAllExcludes(request.exclude())
                .limit(request.errorMessageLimit())
                .build();
        // need live capture time to match up between unfilteredErrorPoints and traceErrorPoints
        // so that transactionCountMap.get(traceErrorPoint.captureTime()) will work
        long liveCaptureTime = clock.currentTimeMillis();
        Result<ErrorMessageCount> queryResult = traceRepository.readErrorMessageCounts(query);
        List<ErrorPoint> unfilteredErrorPoints =
                errorCommonService.readErrorPoints(query.serverRollup(), query.transactionType(),
                        query.transactionName(), query.from(), query.to(), liveCaptureTime);
        DataSeries dataSeries = new DataSeries(null);
        Map<Long, Long[]> dataSeriesExtra = Maps.newHashMap();
        if (query.includes().isEmpty() && query.excludes().isEmpty()) {
            populateDataSeries(query, unfilteredErrorPoints, dataSeries, dataSeriesExtra);
        } else {
            Map<Long, Long> transactionCountMap = Maps.newHashMap();
            for (ErrorPoint unfilteredErrorPoint : unfilteredErrorPoints) {
                transactionCountMap.put(unfilteredErrorPoint.captureTime(),
                        unfilteredErrorPoint.transactionCount());
            }
            long resolutionMillis = aggregateRepository
                    .getDataPointIntervalMillis(query.serverRollup(), query.from(), query.to());
            List<TraceErrorPoint> traceErrorPoints =
                    traceRepository.readErrorPoints(query, resolutionMillis, liveCaptureTime);
            List<ErrorPoint> errorPoints = Lists.newArrayList();
            for (TraceErrorPoint traceErrorPoint : traceErrorPoints) {
                Long transactionCount = transactionCountMap.get(traceErrorPoint.captureTime());
                if (transactionCount != null) {
                    errorPoints.add(ImmutableErrorPoint.of(traceErrorPoint.captureTime(),
                            traceErrorPoint.errorCount(), transactionCount));
                }
            }
            populateDataSeries(query, errorPoints, dataSeries, dataSeriesExtra);
        }

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeries);
        jg.writeObjectField("dataSeriesExtra", dataSeriesExtra);
        jg.writeObjectField("errorMessages", queryResult.records());
        jg.writeBooleanField("moreErrorMessagesAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/error/summaries")
    String getSummaries(String queryString) throws Exception {
        ErrorSummaryRequest request = QueryStrings.decode(queryString, ErrorSummaryRequest.class);

        OverallErrorSummary overallSummary = errorCommonService.readOverallErrorSummary(
                request.serverRollup(), request.transactionType(), request.from(), request.to());

        ErrorSummaryQuery query = ImmutableErrorSummaryQuery.builder()
                .serverRollup(request.serverRollup())
                .transactionType(request.transactionType())
                .from(request.from())
                .to(request.to())
                .sortOrder(request.sortOrder())
                .limit(request.limit())
                .build();
        Result<TransactionErrorSummary> queryResult =
                errorCommonService.readTransactionErrorSummaries(query);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("overall", overallSummary);
        jg.writeObjectField("transactions", queryResult.records());
        jg.writeBooleanField("moreAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/error/tab-bar-data")
    String getTabBarData(String queryString) throws Exception {
        TabBarDataRequest request = QueryStrings.decode(queryString, TabBarDataRequest.class);

        String transactionName = request.transactionName();
        long traceCount;
        if (transactionName == null) {
            traceCount = traceRepository.readOverallErrorCount(request.serverRollup(),
                    request.transactionType(), request.from(), request.to());
        } else {
            traceCount = traceRepository.readTransactionErrorCount(request.serverRollup(),
                    request.transactionType(), transactionName, request.from(), request.to());
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeNumberField("traceCount", traceCount);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private void populateDataSeries(ErrorMessageQuery request, List<ErrorPoint> errorPoints,
            DataSeries dataSeries, Map<Long, Long[]> dataSeriesExtra) throws Exception {
        DataSeriesHelper dataSeriesHelper = new DataSeriesHelper(clock, aggregateRepository
                .getDataPointIntervalMillis(request.serverRollup(), request.from(), request.to()));
        ErrorPoint lastErrorPoint = null;
        for (ErrorPoint errorPoint : errorPoints) {
            if (lastErrorPoint == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(), errorPoint.captureTime(),
                        dataSeries);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastErrorPoint.captureTime(),
                        errorPoint.captureTime(), dataSeries);
            }
            lastErrorPoint = errorPoint;
            long transactionCount = errorPoint.transactionCount();
            dataSeries.add(errorPoint.captureTime(),
                    100 * errorPoint.errorCount() / (double) transactionCount);
            dataSeriesExtra.put(errorPoint.captureTime(),
                    new Long[] {errorPoint.errorCount(), transactionCount});
        }
        if (lastErrorPoint != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(request.to(), dataSeries,
                    lastErrorPoint.captureTime());
        }
    }

    @Value.Immutable
    interface ErrorSummaryRequest {
        String serverRollup();
        String transactionType();
        long from();
        long to();
        AggregateRepository.ErrorSummarySortOrder sortOrder();
        int limit();
    }

    @Value.Immutable
    interface TabBarDataRequest {
        String serverRollup();
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
    }

    @Value.Immutable
    interface ErrorMessageRequest {
        String serverRollup();
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
        // intentionally not plural since maps from query string
        ImmutableList<String> include();
        // intentionally not plural since maps from query string
        ImmutableList<String> exclude();
        int errorMessageLimit();
    }
}
