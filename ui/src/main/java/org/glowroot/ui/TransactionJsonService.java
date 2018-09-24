/*
 * Copyright 2011-2018 the original author or authors.
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

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Doubles;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutableSummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.ImmutableOverallSummary;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.MutableServiceCall;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector.OverallSummary;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector.SummarySortOrder;
import org.glowroot.common.model.TransactionNameSummaryCollector.TransactionNameSummary;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.repo.AggregateRepository;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.Utils;
import org.glowroot.common2.repo.util.RollupLevelService;
import org.glowroot.common2.repo.util.RollupLevelService.DataKind;
import org.glowroot.ui.AggregateMerging.MergedAggregate;
import org.glowroot.ui.AggregateMerging.PercentileValue;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class TransactionJsonService {

    private static final double NANOSECONDS_PER_MILLISECOND = 1000000.0;

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TransactionCommonService transactionCommonService;
    private final AggregateRepository aggregateRepository;
    private final ConfigRepository configRepository;
    private final RollupLevelService rollupLevelService;
    private final Clock clock;

    TransactionJsonService(TransactionCommonService transactionCommonService,
            AggregateRepository aggregateRepository, ConfigRepository configRepository,
            RollupLevelService rollupLevelService, Clock clock) {
        this.transactionCommonService = transactionCommonService;
        this.aggregateRepository = aggregateRepository;
        this.configRepository = configRepository;
        this.rollupLevelService = rollupLevelService;
        this.clock = clock;
    }

    @GET(path = "/backend/transaction/average", permission = "agent:transaction:overview")
    String getOverview(@BindAgentRollupId String agentRollupId,
            @BindRequest TransactionDataRequest request, @BindAutoRefresh boolean autoRefresh)
            throws Exception {
        AggregateQuery query = toChartQuery(request, DataKind.GENERAL);
        long liveCaptureTime = clock.currentTimeMillis();
        List<OverviewAggregate> overviewAggregates =
                transactionCommonService.getOverviewAggregates(agentRollupId, query, autoRefresh);
        if (overviewAggregates.isEmpty() && fallBackToLargestAggregates(query)) {
            // fall back to largest aggregates in case expiration settings have recently changed
            query = withLargestRollupLevel(query);
            overviewAggregates = transactionCommonService.getOverviewAggregates(agentRollupId,
                    query, autoRefresh);
            if (!overviewAggregates.isEmpty() && ignoreFallBackData(query,
                    Iterables.getLast(overviewAggregates).captureTime())) {
                // this is probably data from before the requested time period
                overviewAggregates = ImmutableList.of();
            }
        }
        long dataPointIntervalMillis =
                configRepository.getRollupConfigs().get(query.rollupLevel()).intervalMillis();
        List<DataSeries> dataSeriesList = getDataSeriesForTimerChart(request, overviewAggregates,
                dataPointIntervalMillis, liveCaptureTime);
        Map<Long, Long> transactionCounts = getTransactionCounts(overviewAggregates);
        // TODO more precise aggregate when from/to not on rollup grid
        List<OverviewAggregate> overviewAggregatesForMerging = Lists.newArrayList();
        for (OverviewAggregate overviewAggregate : overviewAggregates) {
            long captureTime = overviewAggregate.captureTime();
            if (captureTime > request.from() && captureTime <= request.to()) {
                overviewAggregatesForMerging.add(overviewAggregate);
            }
        }
        MergedAggregate mergedAggregate =
                AggregateMerging.getMergedAggregate(overviewAggregatesForMerging);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeObjectField("dataSeries", dataSeriesList);
            jg.writeNumberField("dataPointIntervalMillis", dataPointIntervalMillis);
            jg.writeObjectField("transactionCounts", transactionCounts);
            jg.writeObjectField("mergedAggregate", mergedAggregate);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @GET(path = "/backend/transaction/percentiles", permission = "agent:transaction:overview")
    String getPercentiles(@BindAgentRollupId String agentRollupId,
            @BindRequest TransactionPercentileRequest request, @BindAutoRefresh boolean autoRefresh)
            throws Exception {
        AggregateQuery query = toChartQuery(request, DataKind.GENERAL);
        long liveCaptureTime = clock.currentTimeMillis();
        List<PercentileAggregate> percentileAggregates =
                transactionCommonService.getPercentileAggregates(agentRollupId, query, autoRefresh);
        if (percentileAggregates.isEmpty() && fallBackToLargestAggregates(query)) {
            // fall back to largest aggregates in case expiration settings have recently changed
            query = withLargestRollupLevel(query);
            percentileAggregates = transactionCommonService.getPercentileAggregates(agentRollupId,
                    query, autoRefresh);
            if (!percentileAggregates.isEmpty() && ignoreFallBackData(query,
                    Iterables.getLast(percentileAggregates).captureTime())) {
                // this is probably data from before the requested time period
                percentileAggregates = ImmutableList.of();
            }
        }
        long dataPointIntervalMillis =
                configRepository.getRollupConfigs().get(query.rollupLevel()).intervalMillis();
        PercentileData percentileData =
                getDataSeriesForPercentileChart(request, percentileAggregates, request.percentile(),
                        dataPointIntervalMillis, liveCaptureTime);
        Map<Long, Long> transactionCounts = getTransactionCounts2(percentileAggregates);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeObjectField("dataSeries", percentileData.dataSeriesList());
            jg.writeNumberField("dataPointIntervalMillis", dataPointIntervalMillis);
            jg.writeObjectField("transactionCounts", transactionCounts);
            jg.writeObjectField("mergedAggregate", percentileData.mergedAggregate());
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @GET(path = "/backend/transaction/throughput", permission = "agent:transaction:overview")
    String getThroughput(@BindAgentRollupId String agentRollupId,
            @BindRequest TransactionDataRequest request, @BindAutoRefresh boolean autoRefresh)
            throws Exception {
        AggregateQuery query = toChartQuery(request, DataKind.GENERAL);
        long liveCaptureTime = clock.currentTimeMillis();
        List<ThroughputAggregate> throughputAggregates =
                transactionCommonService.getThroughputAggregates(agentRollupId, query, autoRefresh);
        if (throughputAggregates.isEmpty() && fallBackToLargestAggregates(query)) {
            // fall back to largest aggregates in case expiration settings have recently changed
            query = withLargestRollupLevel(query);
            throughputAggregates = transactionCommonService.getThroughputAggregates(agentRollupId,
                    query, autoRefresh);
            if (!throughputAggregates.isEmpty() && ignoreFallBackData(query,
                    Iterables.getLast(throughputAggregates).captureTime())) {
                // this is probably data from before the requested time period
                throughputAggregates = ImmutableList.of();
            }
        }
        long dataPointIntervalMillis =
                configRepository.getRollupConfigs().get(query.rollupLevel()).intervalMillis();
        List<DataSeries> dataSeriesList = getDataSeriesForThroughputChart(request,
                throughputAggregates, dataPointIntervalMillis, liveCaptureTime);
        // TODO more precise aggregate when from/to not on rollup grid
        long transactionCount = 0;
        for (ThroughputAggregate throughputAggregate : throughputAggregates) {
            long captureTime = throughputAggregate.captureTime();
            if (captureTime > request.from() && captureTime <= request.to()) {
                transactionCount += throughputAggregate.transactionCount();
            }
        }

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeObjectField("dataSeries", dataSeriesList);
            jg.writeNumberField("dataPointIntervalMillis", dataPointIntervalMillis);
            jg.writeNumberField("transactionCount", transactionCount);
            jg.writeNumberField("transactionsPerMin",
                    60000.0 * transactionCount / (request.to() - request.from()));
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @GET(path = "/backend/transaction/queries", permission = "agent:transaction:queries")
    String getQueries(@BindAgentRollupId String agentRollupId,
            @BindRequest TransactionDataRequest request) throws Exception {
        AggregateQuery query = toQuery(request, DataKind.QUERY);
        QueryCollector queryCollector =
                transactionCommonService.getMergedQueries(agentRollupId, query);
        List<MutableQuery> queries = queryCollector.getSortedAndTruncatedQueries();
        if (queries.isEmpty() && fallBackToLargestAggregates(query)) {
            // fall back to largest aggregates in case expiration settings have recently changed
            query = withLargestRollupLevel(query);
            queryCollector = transactionCommonService.getMergedQueries(agentRollupId, query);
            queries = queryCollector.getSortedAndTruncatedQueries();
            if (ignoreFallBackData(query, queryCollector.getLastCaptureTime())) {
                // this is probably data from before the requested time period
                queries = ImmutableList.of();
            }
        }
        List<Query> queryList = Lists.newArrayList();
        for (MutableQuery loopQuery : queries) {
            queryList.add(ImmutableQuery.builder()
                    .queryType(loopQuery.getType())
                    .truncatedQueryText(loopQuery.getTruncatedText())
                    .fullQueryTextSha1(loopQuery.getFullTextSha1())
                    .totalDurationNanos(loopQuery.getTotalDurationNanos())
                    .executionCount(loopQuery.getExecutionCount())
                    .totalRows(loopQuery.hasTotalRows() ? loopQuery.getTotalRows() : null)
                    .build());
        }
        if (queryList.isEmpty() && aggregateRepository.shouldHaveQueries(agentRollupId, query)) {
            return "{\"overwritten\":true}";
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeObject(queryList);
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @GET(path = "/backend/transaction/full-query-text", permission = "agent:transaction:queries")
    String getQueryText(@BindAgentRollupId String agentRollupId,
            @BindRequest FullQueryTextRequest request) throws Exception {
        String fullQueryText =
                transactionCommonService.readFullQueryText(agentRollupId, request.fullTextSha1());
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            if (fullQueryText == null) {
                jg.writeBooleanField("expired", true);
            } else {
                jg.writeStringField("fullText", fullQueryText);
            }
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @GET(path = "/backend/transaction/service-calls", permission = "agent:transaction:serviceCalls")
    String getServiceCalls(@BindAgentRollupId String agentRollupId,
            @BindRequest TransactionDataRequest request) throws Exception {
        AggregateQuery query = toQuery(request, DataKind.SERVICE_CALL);
        ServiceCallCollector serviceCallCollector =
                transactionCommonService.getMergedServiceCalls(agentRollupId, query);
        List<MutableServiceCall> serviceCalls =
                serviceCallCollector.getSortedAndTruncatedServiceCalls();
        if (serviceCalls.isEmpty() && fallBackToLargestAggregates(query)) {
            // fall back to largest aggregates in case expiration settings have recently changed
            query = withLargestRollupLevel(query);
            serviceCallCollector =
                    transactionCommonService.getMergedServiceCalls(agentRollupId, query);
            serviceCalls = serviceCallCollector.getSortedAndTruncatedServiceCalls();
            if (ignoreFallBackData(query, serviceCallCollector.getLastCaptureTime())) {
                // this is probably data from before the requested time period
                serviceCalls = ImmutableList.of();
            }
        }
        List<ServiceCall> serviceCallList = Lists.newArrayList();
        for (MutableServiceCall loopServiceCall : serviceCalls) {
            serviceCallList.add(ImmutableServiceCall.builder()
                    .type(loopServiceCall.getType())
                    .text(loopServiceCall.getText())
                    .totalDurationNanos(loopServiceCall.getTotalDurationNanos())
                    .executionCount(loopServiceCall.getExecutionCount())
                    .build());
        }
        Collections.sort(serviceCallList, new Comparator<ServiceCall>() {
            @Override
            public int compare(ServiceCall left, ServiceCall right) {
                // sort descending
                return Doubles.compare(right.totalDurationNanos(), left.totalDurationNanos());
            }
        });
        if (serviceCallList.isEmpty()
                && aggregateRepository.shouldHaveServiceCalls(agentRollupId, query)) {
            return "{\"overwritten\":true}";
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeObject(serviceCallList);
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @GET(path = "/backend/transaction/profile", permission = "agent:transaction:threadProfile")
    String getProfile(@BindAgentRollupId String agentRollupId,
            @BindRequest TransactionProfileRequest request) throws Exception {
        AggregateQuery query = toQuery(request, DataKind.PROFILE);
        ProfileCollector profileCollector =
                transactionCommonService.getMergedProfile(agentRollupId, query, request.auxiliary(),
                        request.include(), request.exclude(), request.truncateBranchPercentage());
        MutableProfile profile = profileCollector.getProfile();
        if (profile.isEmpty() && fallBackToLargestAggregates(query)) {
            // fall back to largest aggregates in case expiration settings have recently changed
            query = withLargestRollupLevel(query);
            profileCollector = transactionCommonService.getMergedProfile(agentRollupId, query,
                    request.auxiliary(), request.include(), request.exclude(),
                    request.truncateBranchPercentage());
            profile = profileCollector.getProfile();
            if (ignoreFallBackData(query, profileCollector.getLastCaptureTime())) {
                // this is probably data from before the requested time period
                profile = new MutableProfile();
            }
        }
        boolean hasUnfilteredMainThreadProfile;
        boolean hasUnfilteredAuxThreadProfile;
        if (request.auxiliary()) {
            hasUnfilteredMainThreadProfile =
                    transactionCommonService.hasMainThreadProfile(agentRollupId, query);
            hasUnfilteredAuxThreadProfile = profile.getUnfilteredSampleCount() > 0;
        } else {
            if (profile.getUnfilteredSampleCount() == 0) {
                hasUnfilteredMainThreadProfile = false;
                // return and display aux profile instead
                profileCollector = transactionCommonService.getMergedProfile(agentRollupId, query,
                        true, request.include(), request.exclude(),
                        request.truncateBranchPercentage());
                profile = profileCollector.getProfile();
                if (profile.isEmpty() && fallBackToLargestAggregates(query)) {
                    // fall back to largest aggregates in case expiration settings have recently
                    // changed
                    query = withLargestRollupLevel(query);
                    profileCollector = transactionCommonService.getMergedProfile(agentRollupId,
                            query, request.auxiliary(), request.include(), request.exclude(),
                            request.truncateBranchPercentage());
                    profile = profileCollector.getProfile();
                    if (ignoreFallBackData(query, profileCollector.getLastCaptureTime())) {
                        // this is probably data from before the requested time period
                        profile = new MutableProfile();
                    }
                }
                hasUnfilteredAuxThreadProfile = profile.getUnfilteredSampleCount() > 0;
            } else {
                hasUnfilteredMainThreadProfile = true;
                hasUnfilteredAuxThreadProfile =
                        transactionCommonService.hasAuxThreadProfile(agentRollupId, query);
            }
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeBooleanField("hasUnfilteredMainThreadProfile", hasUnfilteredMainThreadProfile);
            jg.writeBooleanField("hasUnfilteredAuxThreadProfile", hasUnfilteredAuxThreadProfile);
            if (profile.getUnfilteredSampleCount() == 0
                    && isProfileOverwritten(request, agentRollupId, query)) {
                jg.writeBooleanField("overwritten", true);
            }
            jg.writeFieldName("profile");
            profile.writeJson(jg);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @GET(path = "/backend/transaction/summaries", permission = "agent:transaction:overview")
    String getSummaries(@BindAgentRollupId String agentRollupId,
            @BindRequest TransactionSummaryRequest request, @BindAutoRefresh boolean autoRefresh)
            throws Exception {
        SummaryQuery query = ImmutableSummaryQuery.builder()
                .transactionType(request.transactionType())
                .from(request.from())
                .to(request.to())
                .rollupLevel(rollupLevelService.getRollupLevelForView(request.from(), request.to(),
                        DataKind.GENERAL))
                .build();
        OverallSummaryCollector overallSummaryCollector =
                transactionCommonService.readOverallSummary(agentRollupId, query, autoRefresh);
        OverallSummary overallSummary = overallSummaryCollector.getOverallSummary();
        if (overallSummary.transactionCount() == 0 && fallBackToLargestAggregate(query)) {
            // fall back to largest aggregates in case expiration settings have recently changed
            query = withLargestRollupLevel(query);
            overallSummaryCollector =
                    transactionCommonService.readOverallSummary(agentRollupId, query, autoRefresh);
            overallSummary = overallSummaryCollector.getOverallSummary();
            if (ignoreFallBackData(query, overallSummaryCollector.getLastCaptureTime())) {
                // this is probably data from before the requested time period
                overallSummary = ImmutableOverallSummary.builder()
                        .totalDurationNanos(0)
                        .transactionCount(0)
                        .build();
            }
        }
        Result<TransactionNameSummary> queryResult = transactionCommonService
                .readTransactionNameSummaries(agentRollupId, query, request.sortOrder(),
                        request.limit(), autoRefresh);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeObjectField("overall", overallSummary);
            jg.writeObjectField("transactions", queryResult.records());
            jg.writeBooleanField("moreAvailable", queryResult.moreAvailable());
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @GET(path = "/backend/transaction/flame-graph", permission = "agent:transaction:threadProfile")
    String getFlameGraph(@BindAgentRollupId String agentRollupId,
            @BindRequest FlameGraphRequest request) throws Exception {
        AggregateQuery query = toQuery(request, DataKind.PROFILE);
        ProfileCollector profileCollector =
                transactionCommonService.getMergedProfile(agentRollupId, query, request.auxiliary(),
                        request.include(), request.exclude(), request.truncateBranchPercentage());
        MutableProfile profile = profileCollector.getProfile();
        if (profile.isEmpty() && fallBackToLargestAggregates(query)) {
            // fall back to largest aggregates in case expiration settings have recently changed
            query = withLargestRollupLevel(query);
            profileCollector = transactionCommonService.getMergedProfile(agentRollupId, query,
                    request.auxiliary(), request.include(), request.exclude(),
                    request.truncateBranchPercentage());
            profile = profileCollector.getProfile();
            if (ignoreFallBackData(query, profileCollector.getLastCaptureTime())) {
                // this is probably data from before the requested time period
                profile = new MutableProfile();
            }
        }
        return profile.toFlameGraphJson();
    }

    private AggregateQuery toChartQuery(RequestBase request, DataKind dataKind) throws Exception {
        int rollupLevel =
                rollupLevelService.getRollupLevelForView(request.from(), request.to(), dataKind);
        long rollupIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        // read the closest rollup to the left and right of chart, in order to display line sloping
        // correctly off the chart to the left and right
        long from = RollupLevelService.getFloorRollupTime(request.from(), rollupIntervalMillis);
        long to = RollupLevelService.getCeilRollupTime(request.to(), rollupIntervalMillis);
        return ImmutableAggregateQuery.builder()
                .transactionType(request.transactionType())
                .transactionName(request.transactionName())
                .from(from)
                .to(to)
                .rollupLevel(rollupLevel)
                .build();
    }

    private AggregateQuery toQuery(RequestBase request, DataKind dataKind) throws Exception {
        return ImmutableAggregateQuery.builder()
                .transactionType(request.transactionType())
                .transactionName(request.transactionName())
                .from(request.from())
                .to(request.to())
                .rollupLevel(rollupLevelService.getRollupLevelForView(request.from(), request.to(),
                        dataKind))
                .build();
    }

    private boolean fallBackToLargestAggregates(AggregateQuery query) {
        return query.rollupLevel() < getLargestRollupLevel()
                && query.from() < clock.currentTimeMillis() - getLargestRollupIntervalMillis() * 2;
    }

    private boolean fallBackToLargestAggregate(SummaryQuery query) {
        return query.rollupLevel() < getLargestRollupLevel()
                && query.from() < clock.currentTimeMillis() - getLargestRollupIntervalMillis() * 2;
    }

    private AggregateQuery withLargestRollupLevel(AggregateQuery query) {
        return ImmutableAggregateQuery.builder()
                .copyFrom(query)
                .rollupLevel(getLargestRollupLevel())
                .build();
    }

    private SummaryQuery withLargestRollupLevel(SummaryQuery query) {
        return ImmutableSummaryQuery.builder()
                .copyFrom(query)
                .rollupLevel(getLargestRollupLevel())
                .build();
    }

    private boolean ignoreFallBackData(AggregateQuery query, long lastCaptureTime) {
        return lastCaptureTime < query.from() + getLargestRollupIntervalMillis();
    }

    private boolean ignoreFallBackData(SummaryQuery query, long lastCaptureTime) {
        return lastCaptureTime < query.from() + getLargestRollupIntervalMillis();
    }

    private int getLargestRollupLevel() {
        return configRepository.getRollupConfigs().size() - 1;
    }

    private long getLargestRollupIntervalMillis() {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        return rollupConfigs.get(rollupConfigs.size() - 1).intervalMillis();
    }

    private boolean isProfileOverwritten(TransactionProfileRequest request, String agentRollupId,
            AggregateQuery query) throws Exception {
        if (request.auxiliary()
                && aggregateRepository.shouldHaveAuxThreadProfile(agentRollupId, query)) {
            return true;
        }
        if (!request.auxiliary()
                && aggregateRepository.shouldHaveMainThreadProfile(agentRollupId, query)) {
            return true;
        }
        return false;
    }

    private static PercentileData getDataSeriesForPercentileChart(
            TransactionPercentileRequest request, List<PercentileAggregate> percentileAggregates,
            List<Double> percentiles, long dataPointIntervalMillis, long liveCaptureTime) {
        if (percentileAggregates.isEmpty()) {
            return ImmutablePercentileData.builder()
                    .mergedAggregate(ImmutablePercentileMergedAggregate.builder()
                            .transactionCount(0)
                            .totalDurationNanos(0)
                            .build())
                    .build();
        }
        DataSeriesHelper dataSeriesHelper =
                new DataSeriesHelper(liveCaptureTime, dataPointIntervalMillis);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (double percentile : percentiles) {
            dataSeriesList
                    .add(new DataSeries(Utils.getPercentileWithSuffix(percentile) + " percentile"));
        }

        long transactionCount = 0;
        double totalDurationNanos = 0;
        LazyHistogram mergedHistogram = new LazyHistogram();

        PercentileAggregate priorPercentileAggregate = null;
        for (PercentileAggregate percentileAggregate : percentileAggregates) {
            long captureTime = percentileAggregate.captureTime();
            if (priorPercentileAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(), captureTime,
                        dataSeriesList, null);
            } else {
                dataSeriesHelper.addGapIfNeeded(priorPercentileAggregate.captureTime(), captureTime,
                        dataSeriesList, null);
            }
            LazyHistogram durationNanosHistogram =
                    new LazyHistogram(percentileAggregate.durationNanosHistogram());
            for (int i = 0; i < percentiles.size(); i++) {
                DataSeries dataSeries = dataSeriesList.get(i);
                double percentile = percentiles.get(i);
                // convert to milliseconds
                dataSeries.add(captureTime, durationNanosHistogram.getValueAtPercentile(percentile)
                        / NANOSECONDS_PER_MILLISECOND);
            }
            // TODO more precise aggregate when from/to not on rollup grid
            if (captureTime > request.from() && captureTime <= request.to()) {
                transactionCount += percentileAggregate.transactionCount();
                totalDurationNanos += percentileAggregate.totalDurationNanos();
                mergedHistogram.merge(durationNanosHistogram);
            }
            priorPercentileAggregate = percentileAggregate;
        }
        if (priorPercentileAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(dataSeriesList, null,
                    priorPercentileAggregate.captureTime());
        }

        List<PercentileValue> percentileValues = Lists.newArrayList();
        for (double percentile : percentiles) {
            percentileValues.add(ImmutablePercentileValue.of(
                    Utils.getPercentileWithSuffix(percentile) + " percentile",
                    mergedHistogram.getValueAtPercentile(percentile)));
        }

        return ImmutablePercentileData.builder()
                .dataSeriesList(dataSeriesList)
                .mergedAggregate(ImmutablePercentileMergedAggregate.builder()
                        .transactionCount(transactionCount)
                        .totalDurationNanos(totalDurationNanos)
                        .addAllPercentileValues(percentileValues)
                        .build())
                .build();
    }

    private static List<DataSeries> getDataSeriesForThroughputChart(TransactionDataRequest request,
            List<ThroughputAggregate> throughputAggregates, long dataPointIntervalMillis,
            long liveCaptureTime) {
        if (throughputAggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        DataSeriesHelper dataSeriesHelper =
                new DataSeriesHelper(liveCaptureTime, dataPointIntervalMillis);
        DataSeries dataSeries = new DataSeries("throughput");
        List<DataSeries> dataSeriesList = Lists.newArrayList(dataSeries);
        ThroughputAggregate priorThroughputAggregate = null;
        for (ThroughputAggregate throughputAggregate : throughputAggregates) {
            if (priorThroughputAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(),
                        throughputAggregate.captureTime(), dataSeriesList, null);
            } else {
                dataSeriesHelper.addGapIfNeeded(priorThroughputAggregate.captureTime(),
                        throughputAggregate.captureTime(), dataSeriesList, null);
            }
            long from = throughputAggregate.captureTime() - dataPointIntervalMillis;
            // this math is to deal with live aggregate
            from = CaptureTimes.getRollup(from, dataPointIntervalMillis);
            double transactionsPerMin = 60000.0 * throughputAggregate.transactionCount()
                    / (throughputAggregate.captureTime() - from);
            dataSeries.add(throughputAggregate.captureTime(), transactionsPerMin);
            priorThroughputAggregate = throughputAggregate;
        }
        if (priorThroughputAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(dataSeriesList, null,
                    priorThroughputAggregate.captureTime());
        }
        return dataSeriesList;
    }

    private static List<DataSeries> getDataSeriesForTimerChart(TransactionDataRequest request,
            List<OverviewAggregate> aggregates, long dataPointIntervalMillis,
            long liveCaptureTime) {
        if (aggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        List<StackedPoint> stackedPoints = Lists.newArrayList();
        for (OverviewAggregate aggregate : aggregates) {
            stackedPoints.add(StackedPoint.create(aggregate));
        }
        return getTimerDataSeries(request, stackedPoints, dataPointIntervalMillis, liveCaptureTime);
    }

    private static List<DataSeries> getTimerDataSeries(TransactionDataRequest request,
            List<StackedPoint> stackedPoints, long dataPointIntervalMillis, long liveCaptureTime) {
        DataSeriesHelper dataSeriesHelper =
                new DataSeriesHelper(liveCaptureTime, dataPointIntervalMillis);
        final int topX = 5;
        List<String> timerNames = getTopTimerNames(stackedPoints, topX + 1);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (int i = 0; i < Math.min(timerNames.size(), topX); i++) {
            dataSeriesList.add(new DataSeries(timerNames.get(i)));
        }
        // need 'other' data series even if < topX timers in order to capture root timers,
        // e.g. time spent in 'servlet' timer but not in any nested timer
        DataSeries otherDataSeries = new DataSeries(null);
        OverviewAggregate priorOverviewAggregate = null;
        for (StackedPoint stackedPoint : stackedPoints) {
            OverviewAggregate overviewAggregate = stackedPoint.getOverviewAggregate();
            if (priorOverviewAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(),
                        overviewAggregate.captureTime(), dataSeriesList, otherDataSeries);
            } else {
                dataSeriesHelper.addGapIfNeeded(priorOverviewAggregate.captureTime(),
                        overviewAggregate.captureTime(), dataSeriesList, otherDataSeries);
            }
            MutableDoubleMap<String> stackedTimers = stackedPoint.getStackedTimers();
            double totalOtherNanos = overviewAggregate.totalDurationNanos();
            for (DataSeries dataSeries : dataSeriesList) {
                MutableDouble totalNanos = stackedTimers.get(dataSeries.getName());
                if (totalNanos == null) {
                    dataSeries.add(overviewAggregate.captureTime(), 0);
                } else {
                    // convert to average milliseconds
                    double value = (totalNanos.doubleValue() / overviewAggregate.transactionCount())
                            / NANOSECONDS_PER_MILLISECOND;
                    dataSeries.add(overviewAggregate.captureTime(), value);
                    totalOtherNanos -= totalNanos.doubleValue();
                }
            }
            if (overviewAggregate.transactionCount() == 0) {
                otherDataSeries.add(overviewAggregate.captureTime(), 0);
            } else {
                // convert to average milliseconds
                otherDataSeries.add(overviewAggregate.captureTime(),
                        (totalOtherNanos / overviewAggregate.transactionCount())
                                / NANOSECONDS_PER_MILLISECOND);
            }
            priorOverviewAggregate = overviewAggregate;
        }
        if (priorOverviewAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(dataSeriesList, otherDataSeries,
                    priorOverviewAggregate.captureTime());
        }
        dataSeriesList.add(otherDataSeries);
        return dataSeriesList;
    }

    private static Map<Long, Long> getTransactionCounts(
            List<OverviewAggregate> overviewAggregates) {
        Map<Long, Long> transactionCounts = Maps.newHashMap();
        for (OverviewAggregate overviewAggregate : overviewAggregates) {
            transactionCounts.put(overviewAggregate.captureTime(),
                    overviewAggregate.transactionCount());
        }
        return transactionCounts;
    }

    private static Map<Long, Long> getTransactionCounts2(
            List<PercentileAggregate> percentileAggregates) {
        Map<Long, Long> transactionCounts = Maps.newHashMap();
        for (PercentileAggregate percentileAggregate : percentileAggregates) {
            transactionCounts.put(percentileAggregate.captureTime(),
                    percentileAggregate.transactionCount());
        }
        return transactionCounts;
    }

    // calculate top 5 timers
    private static List<String> getTopTimerNames(List<StackedPoint> stackedPoints, int topX) {
        MutableDoubleMap<String> timerTotals = new MutableDoubleMap<String>();
        for (StackedPoint stackedPoint : stackedPoints) {
            for (Map.Entry<String, MutableDouble> entry : stackedPoint.getStackedTimers()
                    .entrySet()) {
                timerTotals.add(entry.getKey(), entry.getValue().doubleValue());
            }
        }
        Ordering<Map.Entry<String, MutableDouble>> valueOrdering =
                Ordering.natural()
                        .onResultOf(new Function<Map.Entry<String, MutableDouble>, Double>() {
                            @Override
                            public Double apply(
                                    Map. /*@Nullable*/ Entry<String, MutableDouble> entry) {
                                checkNotNull(entry);
                                return entry.getValue().doubleValue();
                            }
                        });
        List<String> timerNames = Lists.newArrayList();
        @SuppressWarnings("assignment.type.incompatible")
        List<Map.Entry<String, MutableDouble>> topTimerTotals =
                valueOrdering.greatestOf(timerTotals.entrySet(), topX);
        for (Map.Entry<String, MutableDouble> entry : topTimerTotals) {
            timerNames.add(entry.getKey());
        }
        return timerNames;
    }

    private static class StackedPoint {

        private final OverviewAggregate overviewAggregate;
        // stacked timer values only include time spent as a leaf node in the timer tree
        private final MutableDoubleMap<String> stackedTimers;

        private static StackedPoint create(OverviewAggregate overviewAggregate) {
            MutableDoubleMap<String> stackedTimers = new MutableDoubleMap<String>();
            for (Aggregate.Timer rootTimer : overviewAggregate.mainThreadRootTimers()) {
                // skip root timers
                for (Aggregate.Timer topLevelTimer : rootTimer.getChildTimerList()) {
                    // traverse tree starting at top-level (under root) timers
                    addToStackedTimer(topLevelTimer, stackedTimers);
                }
            }
            return new StackedPoint(overviewAggregate, stackedTimers);
        }

        private StackedPoint(OverviewAggregate overviewAggregate,
                MutableDoubleMap<String> stackedTimers) {
            this.overviewAggregate = overviewAggregate;
            this.stackedTimers = stackedTimers;
        }

        private OverviewAggregate getOverviewAggregate() {
            return overviewAggregate;
        }

        private MutableDoubleMap<String> getStackedTimers() {
            return stackedTimers;
        }

        private static void addToStackedTimer(Aggregate.Timer timer,
                MutableDoubleMap<String> stackedTimers) {
            double totalNestedNanos = 0;
            for (Aggregate.Timer childTimer : timer.getChildTimerList()) {
                totalNestedNanos += childTimer.getTotalNanos();
                addToStackedTimer(childTimer, stackedTimers);
            }
            String timerName = timer.getName();
            stackedTimers.add(timerName, timer.getTotalNanos() - totalNestedNanos);
        }
    }

    // by using MutableDouble, two operations (get/put) are not required for each increment,
    // instead just a single get is needed (except for first delta)
    @SuppressWarnings("serial")
    private static class MutableDoubleMap<K> extends HashMap<K, MutableDouble> {
        private void add(K key, double delta) {
            MutableDouble existing = get(key);
            if (existing == null) {
                put(key, new MutableDouble(delta));
            } else {
                existing.value += delta;
            }
        }
    }

    private static class MutableDouble {
        private double value;
        private MutableDouble(double value) {
            this.value = value;
        }
        private double doubleValue() {
            return value;
        }
    }

    @Value.Immutable
    interface TransactionSummaryRequest {
        String transactionType();
        long from();
        long to();
        SummarySortOrder sortOrder();
        int limit();
    }

    interface RequestBase {
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
    }

    @Value.Immutable
    interface TransactionDataRequest extends RequestBase {}

    @Value.Immutable
    interface TransactionPercentileRequest extends RequestBase {
        // singular because this is used in query string
        ImmutableList<Double> percentile();
    }

    @Value.Immutable
    interface FullQueryTextRequest {
        String fullTextSha1();
    }

    @Value.Immutable
    interface TransactionProfileRequest extends RequestBase {
        boolean auxiliary();
        // intentionally not plural since maps from query string
        ImmutableList<String> include();
        // intentionally not plural since maps from query string
        ImmutableList<String> exclude();
        double truncateBranchPercentage();
    }

    @Value.Immutable
    interface FlameGraphRequest extends RequestBase {
        boolean auxiliary();
        // intentionally not plural since maps from query string
        ImmutableList<String> include();
        // intentionally not plural since maps from query string
        ImmutableList<String> exclude();
        double truncateBranchPercentage();
    }

    @Value.Immutable
    interface Query {
        String queryType();
        String truncatedQueryText();
        @Nullable
        String fullQueryTextSha1();
        double totalDurationNanos();
        long executionCount();
        @Nullable
        Long totalRows();
    }

    @Value.Immutable
    interface ServiceCall {
        String type();
        String text();
        double totalDurationNanos();
        long executionCount();
    }

    @Value.Immutable
    interface PercentileData {
        ImmutableList<DataSeries> dataSeriesList();
        PercentileMergedAggregate mergedAggregate();
    }

    @Value.Immutable
    interface PercentileMergedAggregate {
        long transactionCount();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalDurationNanos();
        ImmutableList<PercentileValue> percentileValues();
    }
}
