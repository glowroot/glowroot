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

import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.AggregateRepository.ErrorPoint;
import org.glowroot.storage.repo.AggregateRepository.OverallQuery;
import org.glowroot.storage.repo.AggregateRepository.TransactionQuery;
import org.glowroot.storage.repo.ImmutableErrorMessageFilter;
import org.glowroot.storage.repo.ImmutableErrorPoint;
import org.glowroot.storage.repo.ImmutableOverallQuery;
import org.glowroot.storage.repo.ImmutableTraceQuery;
import org.glowroot.storage.repo.ImmutableTransactionQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TraceRepository.ErrorMessageFilter;
import org.glowroot.storage.repo.TraceRepository.ErrorMessagePoint;
import org.glowroot.storage.repo.TraceRepository.ErrorMessageResult;
import org.glowroot.storage.repo.TraceRepository.TraceQuery;
import org.glowroot.storage.repo.helper.RollupLevelService;

@JsonService
class ErrorJsonService {

    private static final ObjectMapper mapper;

    static {
        mapper = ObjectMappers.create();
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    private final ErrorCommonService errorCommonService;
    private final TransactionCommonService transactionCommonService;
    private final TraceRepository traceRepository;
    private final RollupLevelService rollupLevelService;
    private final Clock clock;

    ErrorJsonService(ErrorCommonService errorCommonService,
            TransactionCommonService transactionCommonService, TraceRepository traceRepository,
            RollupLevelService rollupLevelService, Clock clock) {
        this.errorCommonService = errorCommonService;
        this.transactionCommonService = transactionCommonService;
        this.traceRepository = traceRepository;
        this.rollupLevelService = rollupLevelService;
        this.clock = clock;
    }

    @GET("/backend/error/messages")
    String getData(String queryString) throws Exception {
        ErrorMessageRequest request = QueryStrings.decode(queryString, ErrorMessageRequest.class);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(request.serverRollup())
                .transactionType(request.transactionType())
                .transactionName(request.transactionName())
                .from(request.from())
                .to(request.to())
                .build();
        TransactionQuery transactionQuery = ImmutableTransactionQuery.builder()
                .serverRollup(request.serverRollup())
                .transactionType(request.transactionType())
                .transactionName(request.transactionName())
                .from(request.from())
                .to(request.to())
                .rollupLevel(rollupLevelService.getRollupLevelForView(request.from(),
                        request.to()))
                .build();
        ErrorMessageFilter filter = ImmutableErrorMessageFilter.builder()
                .addAllIncludes(request.include())
                .addAllExcludes(request.exclude())
                .build();
        // need live capture time to match up between unfilteredErrorPoints and traceErrorPoints
        // so that transactionCountMap.get(traceErrorPoint.captureTime()) will work
        long liveCaptureTime = clock.currentTimeMillis();
        long resolutionMillis =
                rollupLevelService.getDataPointIntervalMillis(query.from(), query.to());
        ErrorMessageResult result = traceRepository.readErrorMessages(query, filter,
                resolutionMillis, liveCaptureTime, request.errorMessageLimit());

        List<ThroughputAggregate> throughputAggregates =
                transactionCommonService.getThroughputAggregates(transactionQuery, liveCaptureTime);
        DataSeries dataSeries = new DataSeries(null);
        Map<Long, Long[]> dataSeriesExtra = Maps.newHashMap();
        Map<Long, Long> transactionCountMap = Maps.newHashMap();
        for (ThroughputAggregate unfilteredErrorPoint : throughputAggregates) {
            transactionCountMap.put(unfilteredErrorPoint.captureTime(),
                    unfilteredErrorPoint.transactionCount());
        }
        List<ErrorPoint> errorPoints = Lists.newArrayList();
        for (ErrorMessagePoint traceErrorPoint : result.points()) {
            Long transactionCount = transactionCountMap.get(traceErrorPoint.captureTime());
            if (transactionCount != null) {
                errorPoints.add(ImmutableErrorPoint.of(traceErrorPoint.captureTime(),
                        traceErrorPoint.errorCount(), transactionCount));
            }
        }
        populateDataSeries(query, errorPoints, dataSeries, dataSeriesExtra, liveCaptureTime);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeries);
        jg.writeObjectField("dataSeriesExtra", dataSeriesExtra);
        jg.writeObjectField("errorMessages", result.counts().records());
        jg.writeBooleanField("moreErrorMessagesAvailable", result.counts().moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/error/summaries")
    String getSummaries(String queryString) throws Exception {
        ErrorSummaryRequest request = QueryStrings.decode(queryString, ErrorSummaryRequest.class);
        OverallQuery query = ImmutableOverallQuery.builder()
                .serverRollup(request.serverRollup())
                .transactionType(request.transactionType())
                .from(request.from())
                .to(request.to())
                .rollupLevel(rollupLevelService.getRollupLevelForView(request.from(), request.to()))
                .build();
        OverallErrorSummary overallSummary = errorCommonService.readOverallErrorSummary(query);
        Result<TransactionErrorSummary> queryResult = errorCommonService
                .readTransactionErrorSummaries(query, request.sortOrder(), request.limit());

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
        TraceQuery query = QueryStrings.decode(queryString, TraceQuery.class);
        long traceCount = traceRepository.readErrorCount(query);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(
                sb));
        jg.writeStartObject();
        jg.writeNumberField("traceCount", traceCount);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private void populateDataSeries(TraceQuery query, List<ErrorPoint> errorPoints,
            DataSeries dataSeries, Map<Long, Long[]> dataSeriesExtra, long liveCaptureTime)
                    throws Exception {
        DataSeriesHelper dataSeriesHelper = new DataSeriesHelper(liveCaptureTime,
                rollupLevelService.getDataPointIntervalMillis(query.from(), query.to()));
        ErrorPoint lastErrorPoint = null;
        for (ErrorPoint errorPoint : errorPoints) {
            if (lastErrorPoint == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(query.from(), errorPoint.captureTime(),
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
            dataSeriesHelper.addFinalDownslopeIfNeeded(dataSeries, lastErrorPoint.captureTime());
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
