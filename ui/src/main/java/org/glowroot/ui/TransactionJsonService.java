/*
 * Copyright 2011-2016 the original author or authors.
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

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Doubles;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableOverallQuery;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.OverallSummaryCollector.OverallSummary;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector.TransactionSummary;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.Utils;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.ui.AggregateMerging.MergedAggregate;
import org.glowroot.ui.AggregateMerging.PercentileValue;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class TransactionJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final double NANOSECONDS_PER_MILLISECOND = 1000000.0;

    private final TransactionCommonService transactionCommonService;
    private final AggregateRepository aggregateRepository;
    private final RollupLevelService rollupLevelService;
    private final Clock clock;

    TransactionJsonService(TransactionCommonService transactionCommonService,
            AggregateRepository aggregateRepository, RollupLevelService rollupLevelService,
            Clock clock) {
        this.transactionCommonService = transactionCommonService;
        this.aggregateRepository = aggregateRepository;
        this.rollupLevelService = rollupLevelService;
        this.clock = clock;
    }

    @GET(path = "/backend/transaction/average", permission = "agent:view:transaction:average")
    String getOverview(@BindAgentRollup String agentRollup,
            @BindRequest TransactionDataRequest request) throws Exception {
        TransactionQuery query = toQuery(request);
        long liveCaptureTime = clock.currentTimeMillis();
        List<OverviewAggregate> overviewAggregates =
                transactionCommonService.getOverviewAggregates(agentRollup, query);
        List<DataSeries> dataSeriesList =
                getDataSeriesForTimerChart(request, overviewAggregates, liveCaptureTime);
        Map<Long, Long> transactionCounts = getTransactionCounts(overviewAggregates);
        if (!overviewAggregates.isEmpty()
                && overviewAggregates.get(0).captureTime() == request.from()) {
            // the left most aggregate is not really in the requested interval since it is for
            // prior capture times
            overviewAggregates = overviewAggregates.subList(1, overviewAggregates.size());
        }
        MergedAggregate mergedAggregate = AggregateMerging.getMergedAggregate(overviewAggregates);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeObjectField("transactionCounts", transactionCounts);
        jg.writeObjectField("mergedAggregate", mergedAggregate);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET(path = "/backend/transaction/percentiles",
            permission = "agent:view:transaction:percentiles")
    String getPercentiles(@BindAgentRollup String agentRollup,
            @BindRequest TransactionDataRequest request) throws Exception {
        TransactionQuery query = toQuery(request);
        long liveCaptureTime = clock.currentTimeMillis();
        List<PercentileAggregate> percentileAggregates =
                transactionCommonService.getPercentileAggregates(agentRollup, query);
        PercentileData percentileData = getDataSeriesForPercentileChart(request,
                percentileAggregates, request.percentile(), liveCaptureTime);
        Map<Long, Long> transactionCounts = getTransactionCounts2(percentileAggregates);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", percentileData.dataSeriesList());
        jg.writeObjectField("transactionCounts", transactionCounts);
        jg.writeObjectField("mergedAggregate", percentileData.mergedAggregate());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET(path = "/backend/transaction/throughput", permission = "agent:view:transaction:throughput")
    String getThroughput(@BindAgentRollup String agentRollup,
            @BindRequest TransactionDataRequest request) throws Exception {
        TransactionQuery query = toQuery(request);
        long liveCaptureTime = clock.currentTimeMillis();
        List<ThroughputAggregate> throughputAggregates =
                transactionCommonService.getThroughputAggregates(agentRollup, query);
        List<DataSeries> dataSeriesList =
                getDataSeriesForThroughputChart(request, throughputAggregates, liveCaptureTime);
        long transactionCount = 0;
        for (ThroughputAggregate throughputAggregate : throughputAggregates) {
            // not including transaction count where captureTime == request.from() since that
            // will be transaction count for interval outside of chart
            if (throughputAggregate.captureTime() > request.from()) {
                transactionCount += throughputAggregate.transactionCount();
            }
        }
        if (!throughputAggregates.isEmpty()
                && throughputAggregates.get(0).captureTime() == request.from()) {
            // the left most aggregate is not really in the requested interval since it is for
            // prior capture times
            throughputAggregates = throughputAggregates.subList(1, throughputAggregates.size());
        }

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeNumberField("transactionCount", transactionCount);
        jg.writeNumberField("transactionsPerMin",
                60000.0 * transactionCount / (request.to() - request.from()));
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET(path = "/backend/transaction/queries", permission = "agent:view:transaction:queries")
    String getQueries(@BindAgentRollup String agentRollup,
            @BindRequest TransactionDataRequest request) throws Exception {
        TransactionQuery query = toQuery(request);
        Map<String, List<MutableQuery>> queries =
                transactionCommonService.getMergedQueries(agentRollup, query);
        List<Query> queryList = Lists.newArrayList();
        for (Entry<String, List<MutableQuery>> entry : queries.entrySet()) {
            for (MutableQuery loopQuery : entry.getValue()) {
                queryList.add(ImmutableQuery.builder()
                        .queryType(entry.getKey())
                        .truncatedQueryText(loopQuery.getTruncatedQueryText())
                        .fullQueryTextSha1(loopQuery.getFullQueryTextSha1())
                        .totalDurationNanos(loopQuery.getTotalDurationNanos())
                        .executionCount(loopQuery.getExecutionCount())
                        .totalRows(loopQuery.hasTotalRows() ? loopQuery.getTotalRows() : null)
                        .build());
            }
        }
        if (queryList.isEmpty() && aggregateRepository.shouldHaveQueries(agentRollup, query)) {
            return "{\"overwritten\":true}";
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeObject(queryList);
        jg.close();
        return sb.toString();
    }

    @GET(path = "/backend/transaction/full-query-text",
            permission = "agent:view:transaction:queries")
    String getQueryText(@BindAgentRollup String agentRollup,
            @BindRequest FullQueryTextRequest request) throws Exception {
        String fullQueryText =
                transactionCommonService.readFullQueryText(agentRollup, request.fullTextSha1());
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        if (fullQueryText == null) {
            jg.writeBooleanField("expired", true);
        } else {
            jg.writeStringField("fullText", fullQueryText);
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET(path = "/backend/transaction/service-calls",
            permission = "agent:view:transaction:serviceCalls")
    String getServiceCalls(@BindAgentRollup String agentRollup,
            @BindRequest TransactionDataRequest request) throws Exception {
        TransactionQuery query = toQuery(request);
        List<Aggregate.ServiceCallsByType> queries =
                transactionCommonService.getMergedServiceCalls(agentRollup, query);
        List<ServiceCall> serviceCallList = Lists.newArrayList();
        for (Aggregate.ServiceCallsByType queriesByType : queries) {
            for (Aggregate.ServiceCall aggServiceCall : queriesByType.getServiceCallList()) {
                serviceCallList.add(ImmutableServiceCall.builder()
                        .type(queriesByType.getType())
                        .text(aggServiceCall.getText())
                        .totalDurationNanos(aggServiceCall.getTotalDurationNanos())
                        .executionCount(aggServiceCall.getExecutionCount())
                        .build());
            }
        }
        Collections.sort(serviceCallList, new Comparator<ServiceCall>() {
            @Override
            public int compare(ServiceCall left, ServiceCall right) {
                // sort descending
                return Doubles.compare(right.totalDurationNanos(), left.totalDurationNanos());
            }
        });
        if (serviceCallList.isEmpty()
                && aggregateRepository.shouldHaveServiceCalls(agentRollup, query)) {
            return "{\"overwritten\":true}";
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeObject(serviceCallList);
        jg.close();
        return sb.toString();
    }

    @GET(path = "/backend/transaction/profile", permission = "agent:view:transaction:profile")
    String getProfile(@BindAgentRollup String agentRollup,
            @BindRequest TransactionProfileRequest request) throws Exception {
        TransactionQuery query = toQuery(request);
        MutableProfile profile =
                transactionCommonService.getMergedProfile(agentRollup, query, request.auxiliary(),
                        request.include(), request.exclude(), request.truncateBranchPercentage());
        boolean hasUnfilteredAuxThreadProfile;
        if (request.auxiliary()) {
            hasUnfilteredAuxThreadProfile = profile.getUnfilteredSampleCount() > 0;
        } else {
            hasUnfilteredAuxThreadProfile =
                    transactionCommonService.hasAuxThreadProfile(agentRollup, query);
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("hasUnfilteredAuxThreadProfile", hasUnfilteredAuxThreadProfile);
        if (profile.getUnfilteredSampleCount() == 0) {
            if (request.auxiliary()
                    && aggregateRepository.shouldHaveAuxThreadProfile(agentRollup, query)) {
                jg.writeBooleanField("overwritten", true);
            } else if (!request.auxiliary()
                    && aggregateRepository.shouldHaveMainThreadProfile(agentRollup, query)) {
                jg.writeBooleanField("overwritten", true);
            }
        }
        jg.writeFieldName("profile");
        profile.writeJson(jg);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET(path = "/backend/transaction/summaries", permission = "agent:view:transaction:summaries")
    String getSummaries(@BindAgentRollup String agentRollup,
            @BindRequest TransactionSummaryRequest request) throws Exception {
        ImmutableOverallQuery query = ImmutableOverallQuery.builder()
                .transactionType(request.transactionType())
                .from(request.from())
                .to(request.to())
                .rollupLevel(rollupLevelService.getRollupLevelForView(request.from(), request.to()))
                .build();
        OverallSummary overallSummary =
                transactionCommonService.readOverallSummary(agentRollup, query);
        Result<TransactionSummary> queryResult = transactionCommonService
                .readTransactionSummaries(agentRollup, query, request.sortOrder(), request.limit());

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

    @GET(path = "/backend/transaction/flame-graph",
            permission = "agent:view:transaction:flameGraph")
    String getFlameGraph(@BindAgentRollup String agentRollup,
            @BindRequest FlameGraphRequest request) throws Exception {
        TransactionQuery query = toQuery(request);
        MutableProfile profile =
                transactionCommonService.getMergedProfile(agentRollup, query, request.auxiliary(),
                        request.include(), request.exclude(), request.truncateBranchPercentage());
        return profile.toFlameGraphJson();
    }

    private TransactionQuery toQuery(RequestBase request) throws Exception {
        return ImmutableTransactionQuery.builder()
                .transactionType(request.transactionType())
                .transactionName(request.transactionName())
                .from(request.from())
                .to(request.to())
                .rollupLevel(rollupLevelService.getRollupLevelForView(request.from(), request.to()))
                .build();
    }

    private Map<Long, Long> getTransactionCounts(List<OverviewAggregate> overviewAggregates) {
        Map<Long, Long> transactionCounts = Maps.newHashMap();
        for (OverviewAggregate overviewAggregate : overviewAggregates) {
            transactionCounts.put(overviewAggregate.captureTime(),
                    overviewAggregate.transactionCount());
        }
        return transactionCounts;
    }

    private Map<Long, Long> getTransactionCounts2(List<PercentileAggregate> percentileAggregates) {
        Map<Long, Long> transactionCounts = Maps.newHashMap();
        for (PercentileAggregate percentileAggregate : percentileAggregates) {
            transactionCounts.put(percentileAggregate.captureTime(),
                    percentileAggregate.transactionCount());
        }
        return transactionCounts;
    }

    private List<DataSeries> getDataSeriesForTimerChart(TransactionDataRequest request,
            List<OverviewAggregate> aggregates, long liveCaptureTime) throws Exception {
        if (aggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        List<StackedPoint> stackedPoints = Lists.newArrayList();
        for (OverviewAggregate aggregate : aggregates) {
            stackedPoints.add(StackedPoint.create(aggregate));
        }
        return getTimerDataSeries(request, stackedPoints, liveCaptureTime);
    }

    private PercentileData getDataSeriesForPercentileChart(TransactionDataRequest request,
            List<PercentileAggregate> percentileAggregates, List<Double> percentiles,
            long liveCaptureTime) throws Exception {
        if (percentileAggregates.isEmpty()) {
            return ImmutablePercentileData.builder()
                    .mergedAggregate(ImmutablePercentileMergedAggregate.builder()
                            .transactionCount(0)
                            .totalDurationNanos(0)
                            .build())
                    .build();
        }
        DataSeriesHelper dataSeriesHelper = new DataSeriesHelper(liveCaptureTime,
                rollupLevelService.getDataPointIntervalMillis(request.from(), request.to()));
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (double percentile : percentiles) {
            dataSeriesList
                    .add(new DataSeries(Utils.getPercentileWithSuffix(percentile) + " percentile"));
        }

        long transactionCount = 0;
        double totalDurationNanos = 0;
        LazyHistogram mergedHistogram = new LazyHistogram();

        PercentileAggregate lastPercentileAggregate = null;
        for (PercentileAggregate percentileAggregate : percentileAggregates) {
            if (lastPercentileAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(),
                        percentileAggregate.captureTime(), dataSeriesList, null);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastPercentileAggregate.captureTime(),
                        percentileAggregate.captureTime(), dataSeriesList, null);
            }
            lastPercentileAggregate = percentileAggregate;
            LazyHistogram durationNanosHistogram =
                    new LazyHistogram(percentileAggregate.durationNanosHistogram());
            for (int i = 0; i < percentiles.size(); i++) {
                DataSeries dataSeries = dataSeriesList.get(i);
                double percentile = percentiles.get(i);
                // convert to milliseconds
                dataSeries.add(percentileAggregate.captureTime(),
                        durationNanosHistogram.getValueAtPercentile(percentile)
                                / NANOSECONDS_PER_MILLISECOND);
            }
            if (percentileAggregate.captureTime() > request.from()) {
                // filtering out the left most aggregate since it is not really in the requested
                // interval since it is for prior capture times
                transactionCount += percentileAggregate.transactionCount();
                totalDurationNanos += percentileAggregate.totalDurationNanos();
                mergedHistogram.merge(durationNanosHistogram);
            }
        }
        if (lastPercentileAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(dataSeriesList, null,
                    lastPercentileAggregate.captureTime());
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

    private List<DataSeries> getDataSeriesForThroughputChart(TransactionDataRequest request,
            List<ThroughputAggregate> throughputAggregates, long liveCaptureTime) throws Exception {
        if (throughputAggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        long dataPointIntervalMillis =
                rollupLevelService.getDataPointIntervalMillis(request.from(), request.to());
        DataSeriesHelper dataSeriesHelper =
                new DataSeriesHelper(liveCaptureTime, dataPointIntervalMillis);
        DataSeries dataSeries = new DataSeries("throughput");
        List<DataSeries> dataSeriesList = Lists.newArrayList(dataSeries);
        ThroughputAggregate lastThroughputAggregate = null;
        for (ThroughputAggregate throughputAggregate : throughputAggregates) {
            if (lastThroughputAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(),
                        throughputAggregate.captureTime(), dataSeriesList, null);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastThroughputAggregate.captureTime(),
                        throughputAggregate.captureTime(), dataSeriesList, null);
            }
            lastThroughputAggregate = throughputAggregate;
            long from = throughputAggregate.captureTime() - dataPointIntervalMillis;
            // this math is to deal with live aggregate
            from = Utils.getRollupCaptureTime(from, dataPointIntervalMillis);
            double transactionsPerMin = 60000.0 * throughputAggregate.transactionCount()
                    / (throughputAggregate.captureTime() - from);
            dataSeries.add(throughputAggregate.captureTime(), transactionsPerMin);
        }
        if (lastThroughputAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(dataSeriesList, null,
                    lastThroughputAggregate.captureTime());
        }
        return dataSeriesList;
    }

    private List<DataSeries> getTimerDataSeries(TransactionDataRequest request,
            List<StackedPoint> stackedPoints, long liveCaptureTime) throws Exception {
        DataSeriesHelper dataSeriesHelper = new DataSeriesHelper(liveCaptureTime,
                rollupLevelService.getDataPointIntervalMillis(request.from(), request.to()));
        final int topX = 5;
        List<String> timerNames = getTopTimerNames(stackedPoints, topX + 1);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (int i = 0; i < Math.min(timerNames.size(), topX); i++) {
            dataSeriesList.add(new DataSeries(timerNames.get(i)));
        }
        // need 'other' data series even if < topX timers in order to capture root timers,
        // e.g. time spent in 'servlet' timer but not in any nested timer
        DataSeries otherDataSeries = new DataSeries(null);
        OverviewAggregate lastOverviewAggregate = null;
        for (StackedPoint stackedPoint : stackedPoints) {
            OverviewAggregate overviewAggregate = stackedPoint.getOverviewAggregate();
            if (lastOverviewAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(),
                        overviewAggregate.captureTime(), dataSeriesList, otherDataSeries);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastOverviewAggregate.captureTime(),
                        overviewAggregate.captureTime(), dataSeriesList, otherDataSeries);
            }
            lastOverviewAggregate = overviewAggregate;
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
        }
        if (lastOverviewAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(dataSeriesList, otherDataSeries,
                    lastOverviewAggregate.captureTime());
        }
        dataSeriesList.add(otherDataSeries);
        return dataSeriesList;
    }

    // calculate top 5 timers
    private static List<String> getTopTimerNames(List<StackedPoint> stackedPoints, int topX) {
        MutableDoubleMap<String> timerTotals = new MutableDoubleMap<String>();
        for (StackedPoint stackedPoint : stackedPoints) {
            for (Entry<String, MutableDouble> entry : stackedPoint.getStackedTimers().entrySet()) {
                timerTotals.add(entry.getKey(), entry.getValue().doubleValue());
            }
        }
        Ordering<Entry<String, MutableDouble>> valueOrdering =
                Ordering.natural().onResultOf(new Function<Entry<String, MutableDouble>, Double>() {
                    @Override
                    public Double apply(@Nullable Entry<String, MutableDouble> entry) {
                        checkNotNull(entry);
                        return entry.getValue().doubleValue();
                    }
                });
        List<String> timerNames = Lists.newArrayList();
        @SuppressWarnings("assignment.type.incompatible")
        List<Entry<String, MutableDouble>> topTimerTotals =
                valueOrdering.greatestOf(timerTotals.entrySet(), topX);
        for (Entry<String, MutableDouble> entry : topTimerTotals) {
            timerNames.add(entry.getKey());
        }
        return timerNames;
    }

    private static class StackedPoint {

        private final OverviewAggregate overviewAggregate;
        // stacked timer values only include time spent as a leaf node in the timer tree
        private final MutableDoubleMap<String> stackedTimers;

        private static StackedPoint create(OverviewAggregate overviewAggregate) throws IOException {
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
    interface TransactionDataRequest extends RequestBase {
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
