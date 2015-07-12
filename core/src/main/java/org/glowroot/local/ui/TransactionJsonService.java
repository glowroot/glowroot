/*
 * Copyright 2011-2015 the original author or authors.
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
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Longs;
import org.immutables.value.Value;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateTimer;
import org.glowroot.collector.LazyHistogram;
import org.glowroot.collector.QueryComponent.AggregateQuery;
import org.glowroot.collector.TransactionSummary;
import org.glowroot.common.Clock;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.TransactionSummarySortOrder;
import org.glowroot.local.store.AlertingService;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.TraceDao;
import org.glowroot.local.store.TransactionSummaryQuery;
import org.glowroot.transaction.TransactionCollector;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.transaction.model.ProfileNode;
import org.glowroot.transaction.model.Transaction;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class TransactionJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final double MICROSECONDS_PER_MILLISECOND = 1000.0;

    private final TransactionCommonService transactionCommonService;
    private final TraceDao traceDao;
    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;
    private final Clock clock;

    private final long fixedAggregateIntervalMillis;
    private final long fixedAggregateRollupMillis;

    TransactionJsonService(TransactionCommonService transactionCommonService, TraceDao traceDao,
            TransactionRegistry transactionRegistry, TransactionCollector transactionCollector,
            Clock clock, long fixedAggregateIntervalSeconds, long fixedAggregateRollupSeconds) {
        this.transactionCommonService = transactionCommonService;
        this.traceDao = traceDao;
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
        this.clock = clock;
        this.fixedAggregateIntervalMillis = fixedAggregateIntervalSeconds * 1000;
        this.fixedAggregateRollupMillis = fixedAggregateRollupSeconds * 1000;
    }

    @GET("/backend/transaction/percentiles")
    String getPercentiles(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);

        long liveCaptureTime = clock.currentTimeMillis();
        List<Aggregate> aggregates = transactionCommonService.getAggregates(
                request.transactionType(), request.transactionName(), request.from(), request.to(),
                liveCaptureTime);
        List<DataSeries> dataSeriesList =
                getDataSeriesForPercentileChart(request, aggregates, request.percentile());
        Map<Long, Long> transactionCounts = getTransactionCounts(aggregates);
        if (!aggregates.isEmpty() && aggregates.get(0).captureTime() == request.from()) {
            // the left most aggregate is not really in the requested interval since it is for
            // prior capture times
            aggregates = aggregates.subList(1, aggregates.size());
        }
        PercentileMergedAggregate mergedAggregate =
                AggregateMerging.getPercentileMergedAggregate(aggregates, request.percentile());

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

    private Map<Long, Long> getTransactionCounts(List<Aggregate> aggregates) {
        Map<Long, Long> transactionCounts = Maps.newHashMap();
        for (Aggregate aggregate : aggregates) {
            transactionCounts.put(aggregate.captureTime(), aggregate.transactionCount());
        }
        return transactionCounts;
    }

    @GET("/backend/transaction/metrics")
    String getMetrics(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);

        long liveCaptureTime = clock.currentTimeMillis();
        List<Aggregate> aggregates = transactionCommonService.getAggregates(
                request.transactionType(), request.transactionName(), request.from(), request.to(),
                liveCaptureTime);
        List<DataSeries> dataSeriesList = getDataSeriesForMetricsChart(request, aggregates);
        Map<Long, Long> transactionCounts = getTransactionCounts(aggregates);
        if (!aggregates.isEmpty() && aggregates.get(0).captureTime() == request.from()) {
            // the left most aggregate is not really in the requested interval since it is for
            // prior capture times
            aggregates = aggregates.subList(1, aggregates.size());
        }
        TimerMergedAggregate timerMergedAggregate =
                AggregateMerging.getTimerMergedAggregate(aggregates);
        ThreadInfoAggregate threadInfoAggregate =
                AggregateMerging.getThreadInfoAggregate(aggregates);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeObjectField("transactionCounts", transactionCounts);
        jg.writeObjectField("mergedAggregate", timerMergedAggregate);
        if (!threadInfoAggregate.isEmpty()) {
            jg.writeObjectField("threadInfoAggregate", threadInfoAggregate);
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/queries")
    String getQueries(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);
        Map<String, List<AggregateQuery>> queries = transactionCommonService.getQueries(
                request.transactionType(), request.transactionName(), request.from(), request.to());
        List<Query> queryList = Lists.newArrayList();
        for (Entry<String, List<AggregateQuery>> entry : queries.entrySet()) {
            List<AggregateQuery> queriesForQueryType = entry.getValue();
            for (AggregateQuery aggregateQuery : queriesForQueryType) {
                queryList.add(Query.builder()
                        .queryType(entry.getKey())
                        .queryText(aggregateQuery.getQueryText())
                        .totalMicros(aggregateQuery.getTotalMicros())
                        .executionCount(aggregateQuery.getExecutionCount())
                        .totalRows(aggregateQuery.getTotalRows())
                        .build());
            }
        }
        Collections.sort(queryList, new Comparator<Query>() {
            @Override
            public int compare(@Nullable Query left, @Nullable Query right) {
                checkNotNull(left);
                checkNotNull(right);
                // sort descending
                return Longs.compare(right.totalMicros(), left.totalMicros());
            }
        });
        if (queryList.isEmpty()
                && transactionCommonService.shouldHaveQueries(request.transactionType(),
                        request.transactionName(), request.from(), request.to())) {
            return "{\"overwritten\":true}";
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeObject(queryList);
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/profile")
    String getProfile(String queryString) throws Exception {
        TransactionProfileRequest request =
                QueryStrings.decode(queryString, TransactionProfileRequest.class);
        ProfileNode profile = transactionCommonService.getProfile(request.transactionType(),
                request.transactionName(), request.from(), request.to(), request.filter(),
                request.truncateLeafPercentage());
        if (profile.getSampleCount() == 0 && request.filter() != null
                && transactionCommonService.shouldHaveProfile(request.transactionType(),
                        request.transactionName(), request.from(), request.to())) {
            return "{\"overwritten\":true}";
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeObject(profile);
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/summaries")
    String getSummaries(String queryString) throws Exception {
        TransactionSummaryRequest request =
                QueryStrings.decode(queryString, TransactionSummaryRequest.class);

        TransactionSummary overallSummary = transactionCommonService.readOverallSummary(
                request.transactionType(), request.from() + 1, request.to());

        TransactionSummaryQuery query = TransactionSummaryQuery.builder()
                .transactionType(request.transactionType())
                .from(request.from() + 1)
                .to(request.to())
                .sortOrder(request.sortOrder())
                .limit(request.limit())
                .build();
        QueryResult<TransactionSummary> queryResult =
                transactionCommonService.readTransactionSummaries(query);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeFieldName("overall");
        jg.writeObject(overallSummary);
        jg.writeFieldName("transactions");
        jg.writeObject(queryResult.records());
        jg.writeBooleanField("moreAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/tab-bar-data")
    String getTabBarData(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);

        String transactionName = request.transactionName();
        long traceCount;
        if (transactionName == null) {
            traceCount = traceDao.readOverallCount(request.transactionType(), request.from(),
                    request.to());
        } else {
            traceCount = traceDao.readTransactionCount(request.transactionType(),
                    transactionName, request.from(), request.to());
        }
        boolean includeActiveTraces = shouldIncludeActiveTraces(request);
        if (includeActiveTraces) {
            // include active traces, this is mostly for the case where there is just a single very
            // long running active trace and it would be misleading to display Traces (0) on the tab
            for (Transaction transaction : transactionRegistry.getTransactions()) {
                // don't include partially stored traces since those are already counted above
                if (matchesActive(transaction, request) && !transaction.isPartiallyStored()) {
                    traceCount++;
                }
            }
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
        // TODO add text filter to flame graph
        ProfileNode profile = transactionCommonService.getProfile(request.transactionType(),
                request.transactionName(), request.from(), request.to(), null,
                request.truncateLeafPercentage());
        ProfileNode interestingNode = profile;
        while (interestingNode.hasOneChildNode()) {
            interestingNode = interestingNode.getOnlyChildNode();
        }
        if (interestingNode.isChildNodesEmpty()) {
            // only a single branch through entire tree
            interestingNode = profile;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectFieldStart("");
        jg.writeNumberField("svUnique", 0);
        jg.writeNumberField("svTotal", interestingNode.getSampleCount());
        jg.writeObjectFieldStart("svChildren");
        writeFlameGraphNode(interestingNode, jg);
        jg.writeEndObject();
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private List<DataSeries> getDataSeriesForPercentileChart(TransactionDataRequest request,
            List<Aggregate> aggregates, List<Double> percentiles) throws Exception {
        if (aggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        DataSeriesHelper dataSeriesHelper =
                new DataSeriesHelper(clock, getDataPointIntervalMillis(request));
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (double percentile : percentiles) {
            dataSeriesList.add(new DataSeries(AlertingService.getPercentileWithSuffix(percentile)
                    + " percentile"));
        }
        Aggregate lastAggregate = null;
        for (Aggregate aggregate : aggregates) {
            if (lastAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(), aggregate.captureTime(),
                        dataSeriesList, null);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastAggregate.captureTime(),
                        aggregate.captureTime(), dataSeriesList, null);
            }
            lastAggregate = aggregate;
            LazyHistogram histogram = new LazyHistogram();
            histogram.decodeFromByteBuffer(ByteBuffer.wrap(aggregate.histogram()));
            for (int i = 0; i < percentiles.size(); i++) {
                DataSeries dataSeries = dataSeriesList.get(i);
                double percentile = percentiles.get(i);
                dataSeries.add(aggregate.captureTime(),
                        histogram.getValueAtPercentile(percentile) / MICROSECONDS_PER_MILLISECOND);
            }
        }
        if (lastAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(request.to(), dataSeriesList, null,
                    lastAggregate.captureTime());
        }
        return dataSeriesList;
    }

    private List<DataSeries> getDataSeriesForMetricsChart(TransactionDataRequest request,
            List<Aggregate> aggregates) throws IOException {
        if (aggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        List<StackedPoint> stackedPoints = Lists.newArrayList();
        for (Aggregate aggregate : aggregates) {
            stackedPoints.add(StackedPoint.create(aggregate));
        }
        return getMetricDataSeries(request, stackedPoints);
    }

    private List<DataSeries> getMetricDataSeries(TransactionDataRequest request,
            List<StackedPoint> stackedPoints) {
        DataSeriesHelper dataSeriesHelper =
                new DataSeriesHelper(clock, getDataPointIntervalMillis(request));
        final int topX = 5;
        List<String> timerNames = getTopTimerNames(stackedPoints, topX + 1);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (int i = 0; i < Math.min(timerNames.size(), topX); i++) {
            dataSeriesList.add(new DataSeries(timerNames.get(i)));
        }
        // need 'other' data series even if < topX timers in order to capture root timers,
        // e.g. time spent in 'servlet' timer but not in any nested timer
        DataSeries otherDataSeries = new DataSeries(null);
        Aggregate lastAggregate = null;
        for (StackedPoint stackedPoint : stackedPoints) {
            Aggregate aggregate = stackedPoint.getAggregate();
            if (lastAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(), aggregate.captureTime(),
                        dataSeriesList, otherDataSeries);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastAggregate.captureTime(),
                        aggregate.captureTime(), dataSeriesList, otherDataSeries);
            }
            lastAggregate = aggregate;
            MutableLongMap<String> stackedTimers = stackedPoint.getStackedTimers();
            long totalOtherMicros = aggregate.totalMicros();
            for (DataSeries dataSeries : dataSeriesList) {
                MutableLong totalMicros = stackedTimers.get(dataSeries.getName());
                if (totalMicros == null) {
                    dataSeries.add(aggregate.captureTime(), 0);
                } else {
                    // convert to average seconds
                    dataSeries.add(aggregate.captureTime(),
                            (totalMicros.longValue() / (double) aggregate.transactionCount())
                                    / MICROSECONDS_PER_MILLISECOND);
                    totalOtherMicros -= totalMicros.longValue();
                }
            }
            if (aggregate.transactionCount() == 0) {
                otherDataSeries.add(aggregate.captureTime(), 0);
            } else {
                // convert to average seconds
                otherDataSeries.add(aggregate.captureTime(),
                        (totalOtherMicros / (double) aggregate.transactionCount())
                                / MICROSECONDS_PER_MILLISECOND);
            }
        }
        if (lastAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(request.to(), dataSeriesList,
                    otherDataSeries, lastAggregate.captureTime());
        }
        dataSeriesList.add(otherDataSeries);
        return dataSeriesList;
    }

    private long getDataPointIntervalMillis(TransactionDataRequest request) {
        if (request.to() - request.from() > AggregateDao.ROLLUP_THRESHOLD_MILLIS) {
            return fixedAggregateRollupMillis;
        } else {
            return fixedAggregateIntervalMillis;
        }
    }

    // calculate top 5 timers
    private static List<String> getTopTimerNames(List<StackedPoint> stackedPoints, int topX) {
        MutableLongMap<String> timerTotals = new MutableLongMap<String>();
        for (StackedPoint stackedPoint : stackedPoints) {
            for (Entry<String, MutableLong> entry : stackedPoint.getStackedTimers().entrySet()) {
                timerTotals.add(entry.getKey(), entry.getValue().longValue());
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
        List<String> timerNames = Lists.newArrayList();
        @SuppressWarnings("assignment.type.incompatible")
        List<Entry<String, MutableLong>> topTimerTotals =
                valueOrdering.greatestOf(timerTotals.entrySet(), topX);
        for (Entry<String, MutableLong> entry : topTimerTotals) {
            timerNames.add(entry.getKey());
        }
        return timerNames;
    }

    private boolean shouldIncludeActiveTraces(TransactionDataRequest request) {
        long currentTimeMillis = clock.currentTimeMillis();
        return (request.to() == 0 || request.to() > currentTimeMillis)
                && request.from() < currentTimeMillis;
    }

    @VisibleForTesting
    boolean matchesActive(Transaction transaction, TransactionDataRequest request) {
        if (!transactionCollector.shouldStore(transaction)) {
            return false;
        }
        if (!request.transactionType().equals(transaction.getTransactionType())) {
            return false;
        }
        String transactionName = request.transactionName();
        if (transactionName != null && !transactionName.equals(transaction.getTransactionName())) {
            return false;
        }
        return true;
    }

    // TODO use non-recursive algorithm to guard from stack overflow error
    private static void writeFlameGraphNode(ProfileNode node, JsonGenerator jg) throws IOException {
        jg.writeObjectFieldStart(node.getStackTraceElementStr());
        long svUnique = node.getSampleCount();
        for (ProfileNode childNode : node.getChildNodes()) {
            svUnique -= childNode.getSampleCount();
        }
        jg.writeNumberField("svUnique", svUnique);
        jg.writeNumberField("svTotal", node.getSampleCount());
        jg.writeObjectFieldStart("svChildren");
        for (ProfileNode childNode : node.getChildNodes()) {
            writeFlameGraphNode(childNode, jg);
        }
        jg.writeEndObject();
        jg.writeEndObject();
    }

    private static class StackedPoint {

        private final Aggregate aggregate;
        // stacked timer values only include time spent as a leaf node in the timer tree
        private final MutableLongMap<String> stackedTimers;

        private static StackedPoint create(Aggregate aggregate) throws IOException {
            String timers = aggregate.timers();
            MutableLongMap<String> stackedTimers = new MutableLongMap<String>();
            AggregateTimer syntheticRootTimer = mapper.readValue(timers, AggregateTimer.class);
            // skip synthetic root timer
            for (AggregateTimer realRootTimer : syntheticRootTimer.getNestedTimers()) {
                // skip real root timers
                for (AggregateTimer topLevelTimer : realRootTimer.getNestedTimers()) {
                    // traverse tree starting at top-level (under root) timers
                    addToStackedTimer(topLevelTimer, stackedTimers);
                }
            }
            return new StackedPoint(aggregate, stackedTimers);
        }

        private StackedPoint(Aggregate aggregate, MutableLongMap<String> stackedTimers) {
            this.aggregate = aggregate;
            this.stackedTimers = stackedTimers;
        }

        private Aggregate getAggregate() {
            return aggregate;
        }

        private MutableLongMap<String> getStackedTimers() {
            return stackedTimers;
        }

        private static void addToStackedTimer(AggregateTimer timer,
                MutableLongMap<String> stackedTimers) {
            long totalNestedMicros = 0;
            for (AggregateTimer nestedTimer : timer.getNestedTimers()) {
                totalNestedMicros += nestedTimer.getTotalMicros();
                addToStackedTimer(nestedTimer, stackedTimers);
            }
            // timer name is only null for synthetic root timer which is never passed to this method
            String timerName = checkNotNull(timer.getName());
            stackedTimers.add(timerName, timer.getTotalMicros() - totalNestedMicros);
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
    abstract static class TransactionSummaryRequestBase {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract TransactionSummarySortOrder sortOrder();
        abstract int limit();
    }

    @Value.Immutable
    abstract static class TransactionDataRequestBase {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
        // singular because this is used in query string
        abstract ImmutableList<Double> percentile();
    }

    @Value.Immutable
    abstract static class TransactionProfileRequestBase {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
        abstract @Nullable String filter();
        abstract double truncateLeafPercentage();
    }

    @Value.Immutable
    abstract static class FlameGraphRequestBase {
        abstract long from();
        abstract long to();
        abstract String transactionType();
        abstract @Nullable String transactionName();
        abstract double truncateLeafPercentage();
    }

    @Value.Immutable
    abstract static class QueryBase {
        abstract String queryType();
        abstract String queryText();
        abstract long totalMicros();
        abstract long executionCount();
        abstract long totalRows();
    }
}
