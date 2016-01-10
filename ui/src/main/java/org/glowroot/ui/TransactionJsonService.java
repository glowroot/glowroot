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

import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.AggregateRepository.OverallSummary;
import org.glowroot.storage.repo.AggregateRepository.OverviewAggregate;
import org.glowroot.storage.repo.AggregateRepository.PercentileAggregate;
import org.glowroot.storage.repo.AggregateRepository.SummarySortOrder;
import org.glowroot.storage.repo.AggregateRepository.ThroughputAggregate;
import org.glowroot.storage.repo.AggregateRepository.TransactionQuery;
import org.glowroot.storage.repo.AggregateRepository.TransactionSummary;
import org.glowroot.storage.repo.ImmutableOverallQuery;
import org.glowroot.storage.repo.ImmutableTraceQuery;
import org.glowroot.storage.repo.ImmutableTransactionQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TraceRepository.TraceQuery;
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
    private final TraceRepository traceRepository;
    private final LiveTraceRepository liveTraceRepository;
    private final RollupLevelService rollupLevelService;
    private final Clock clock;

    TransactionJsonService(TransactionCommonService transactionCommonService,
            AggregateRepository aggregateRepository, TraceRepository traceRepository,
            LiveTraceRepository liveTraceRepository, RollupLevelService rollupLevelService,
            Clock clock) {
        this.transactionCommonService = transactionCommonService;
        this.aggregateRepository = aggregateRepository;
        this.traceRepository = traceRepository;
        this.liveTraceRepository = liveTraceRepository;
        this.rollupLevelService = rollupLevelService;
        this.clock = clock;
    }

    @GET("/backend/transaction/average")
    String getOverview(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);
        TransactionQuery query = toQuery(request);
        long liveCaptureTime = clock.currentTimeMillis();
        List<OverviewAggregate> overviewAggregates =
                transactionCommonService.getOverviewAggregates(query);
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

    @GET("/backend/transaction/percentiles")
    String getPercentiles(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);
        TransactionQuery query = toQuery(request);
        long liveCaptureTime = clock.currentTimeMillis();
        List<PercentileAggregate> percentileAggregates =
                transactionCommonService.getPercentileAggregates(query);
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

    @GET("/backend/transaction/throughput")
    String getThroughput(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);
        TransactionQuery query = toQuery(request);
        long liveCaptureTime = clock.currentTimeMillis();
        List<ThroughputAggregate> throughputAggregates =
                transactionCommonService.getThroughputAggregates(query);
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

    @GET("/backend/transaction/profile")
    String getProfile(String queryString) throws Exception {
        TransactionProfileRequest request =
                QueryStrings.decode(queryString, TransactionProfileRequest.class);
        TransactionQuery query = toQuery(request);
        MutableProfile profile =
                transactionCommonService.getMergedProfile(query, request.auxiliary(),
                        request.include(), request.exclude(), request.truncateBranchPercentage());
        boolean hasUnfilteredAuxThreadProfile;
        if (request.auxiliary()) {
            hasUnfilteredAuxThreadProfile = profile.getUnfilteredSampleCount() > 0;
        } else {
            hasUnfilteredAuxThreadProfile = transactionCommonService.hasAuxThreadProfile(query);
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("hasUnfilteredAuxThreadProfile", hasUnfilteredAuxThreadProfile);
        if (profile.getUnfilteredSampleCount() == 0) {
            if (request.auxiliary() && aggregateRepository.shouldHaveAuxThreadProfile(query)) {
                jg.writeBooleanField("overwritten", true);
            } else if (!request.auxiliary()
                    && aggregateRepository.shouldHaveMainThreadProfile(query)) {
                jg.writeBooleanField("overwritten", true);
            }
        }
        jg.writeFieldName("profile");
        profile.writeJson(jg);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/queries")
    String getQueries(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);
        TransactionQuery query = toQuery(request);
        List<Aggregate.QueriesByType> queries = transactionCommonService.getMergedQueries(query);
        List<Query> queryList = Lists.newArrayList();
        for (Aggregate.QueriesByType queriesByType : queries) {
            for (Aggregate.Query aggQuery : queriesByType.getQueryList()) {
                queryList.add(ImmutableQuery.builder()
                        .queryType(queriesByType.getType())
                        .queryText(aggQuery.getText())
                        .totalNanos(aggQuery.getTotalNanos())
                        .executionCount(aggQuery.getExecutionCount())
                        .totalRows(aggQuery.getTotalRows())
                        .build());
            }
        }
        Collections.sort(queryList, new Comparator<Query>() {
            @Override
            public int compare(Query left, Query right) {
                // sort descending
                return Doubles.compare(right.totalNanos(), left.totalNanos());
            }
        });
        if (queryList.isEmpty() && aggregateRepository.shouldHaveQueries(query)) {
            return "{\"overwritten\":true}";
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeObject(queryList);
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/summaries")
    String getSummaries(String queryString) throws Exception {
        TransactionSummaryRequest request =
                QueryStrings.decode(queryString, TransactionSummaryRequest.class);
        ImmutableOverallQuery quer = ImmutableOverallQuery.builder()
                .serverRollup(request.serverRollup())
                .transactionType(request.transactionType())
                .from(request.from())
                .to(request.to())
                .rollupLevel(rollupLevelService.getRollupLevelForView(request.from(), request.to()))
                .build();
        OverallSummary overallSummary = transactionCommonService.readOverallSummary(quer);
        Result<TransactionSummary> queryResult = transactionCommonService
                .readTransactionSummaries(quer, request.sortOrder(), request.limit());

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

    @GET("/backend/transaction/tab-bar-data")
    String getTabBarData(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(request.serverRollup())
                .transactionType(request.transactionType())
                .transactionName(request.transactionName())
                .from(request.from())
                .to(request.to())
                .build();
        long traceCount = traceRepository.readSlowCount(query);
        boolean includeActiveTraces = shouldIncludeActiveTraces(request);
        if (includeActiveTraces) {
            traceCount += liveTraceRepository.getMatchingTraceCount(request.serverRollup(),
                    request.transactionType(), request.transactionName());
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeNumberField("traceCount", traceCount);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/flame-graph")
    String getFlameGraph(String queryString) throws Exception {
        FlameGraphRequest request = QueryStrings.decode(queryString, FlameGraphRequest.class);
        TransactionQuery query = toQuery(request);
        MutableProfile profile =
                transactionCommonService.getMergedProfile(query, request.auxiliary(),
                        request.include(), request.exclude(), request.truncateBranchPercentage());
        return profile.toFlameGraphJson();
    }

    private TransactionQuery toQuery(RequestBase request) throws Exception {
        return ImmutableTransactionQuery.builder()
                .serverRollup(request.serverRollup())
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
                            .totalNanos(0)
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
        double totalNanos = 0;
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
            LazyHistogram histogram = new LazyHistogram(percentileAggregate.histogram());
            for (int i = 0; i < percentiles.size(); i++) {
                DataSeries dataSeries = dataSeriesList.get(i);
                double percentile = percentiles.get(i);
                // convert to milliseconds
                dataSeries.add(percentileAggregate.captureTime(),
                        histogram.getValueAtPercentile(percentile) / NANOSECONDS_PER_MILLISECOND);
            }

            if (percentileAggregate.captureTime() > request.from()) {
                // filtering out the left most aggregate since it is not really in the requested
                // interval since it is for prior capture times
                transactionCount += percentileAggregate.transactionCount();
                totalNanos += percentileAggregate.totalNanos();
                mergedHistogram.merge(histogram);
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
                        .totalNanos(totalNanos)
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
            // this math is to deal with active aggregate
            from = (long) (Math.ceil(from / dataPointIntervalMillis) * dataPointIntervalMillis);
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

    private boolean shouldIncludeActiveTraces(TransactionDataRequest request) {
        long currentTimeMillis = clock.currentTimeMillis();
        return (request.to() == 0 || request.to() > currentTimeMillis)
                && request.from() < currentTimeMillis;
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
        String serverRollup();
        String transactionType();
        long from();
        long to();
        SummarySortOrder sortOrder();
        int limit();
    }

    interface RequestBase {
        String serverRollup();
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
        String queryText();
        double totalNanos();
        long executionCount();
        long totalRows();
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
        double totalNanos();
        ImmutableList<PercentileValue> percentileValues();
    }
}
