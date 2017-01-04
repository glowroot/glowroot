/*
 * Copyright 2016-2017 the original author or authors.
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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.GaugeValueRepository.Gauge;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.ui.GaugeValueJsonService.GaugeOrdering;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

@JsonService
class ReportJsonService {

    private static final String RESPONSE_TIME_AVG = "transaction:response-time-avg";
    private static final String RESPONSE_TIME_PERCENTILE = "transaction:response-time-percentile";
    private static final String THROUGHPUT = "transaction:throughput";

    private static final double NANOSECONDS_PER_MILLISECOND = 1000000.0;

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateRepository aggregateRepository;
    private final AgentRepository agentRepository;
    private final GaugeValueRepository gaugeValueRepository;

    ReportJsonService(AggregateRepository aggregateRepository, AgentRepository agentRepository,
            GaugeValueRepository gaugeValueRepository) {
        this.aggregateRepository = aggregateRepository;
        this.agentRepository = agentRepository;
        this.gaugeValueRepository = gaugeValueRepository;
    }

    // permission is checked based on agentRollupIds in the request
    @GET(path = "/backend/report/all-gauges", permission = "")
    String getGauges(@BindRequest RequestWithAgentRollupIds request,
            @BindAuthentication Authentication authentication) throws Exception {
        checkPermissions(request.agentRollupIds(), "agent:jvm:gauges", authentication);
        Set<Gauge> gauges = Sets.newHashSet();
        for (String agentRollupId : request.agentRollupIds()) {
            gauges.addAll(gaugeValueRepository.getGauges(agentRollupId));
        }
        ImmutableList<Gauge> sortedGauges = new GaugeOrdering().immutableSortedCopy(gauges);
        return mapper.writeValueAsString(sortedGauges);
    }

    // permission is checked based on agentRollupIds in the request
    @GET(path = "/backend/report", permission = "")
    String getReport(@BindRequest ReportRequest request,
            @BindAuthentication Authentication authentication) throws Exception {
        String metricId = request.metricId();
        if (metricId.startsWith("transaction:")) {
            checkPermissions(request.agentRollupIds(), "agent:transaction:overview",
                    authentication);
        } else if (metricId.startsWith("gauge:")) {
            checkPermissions(request.agentRollupIds(), "agent:jvm:gauges", authentication);
        } else {
            throw new IllegalStateException("Unexpected metric id: " + metricId);
        }
        TimeZone timeZone = TimeZone.getTimeZone(request.timeZoneId());
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");
        simpleDateFormat.setTimeZone(timeZone);
        Date from = simpleDateFormat.parse(request.fromDate());
        Date to = simpleDateFormat.parse(request.toDate());
        Calendar cal = Calendar.getInstance();
        cal.setTime(to);
        cal.add(Calendar.DATE, 1);
        to = cal.getTime();

        RollupCaptureTimeFn rollupCaptureTimeFn =
                new RollupCaptureTimeFn(request.rollup(), timeZone, request.fromDate());

        double gapMillis;
        switch (request.rollup()) {
            case HOURLY:
                gapMillis = HOURS.toMillis(1) * 1.5;
                break;
            case DAILY:
                gapMillis = DAYS.toMillis(1) * 1.5;
                break;
            case WEEKLY:
                gapMillis = DAYS.toMillis(1) * 7 * 1.5;
                break;
            case MONTHLY:
                gapMillis = DAYS.toMillis(1) * 30 * 1.5;
                break;
            default:
                throw new IllegalStateException("Unexpected rollup: " + request.rollup());
        }

        List<DataSeries> dataSeriesList;
        if (metricId.startsWith("transaction:")) {
            dataSeriesList = getTransactionReport(request, timeZone, from, to, rollupCaptureTimeFn,
                    gapMillis, metricId);
        } else if (metricId.startsWith("gauge:")) {
            String gaugeName = metricId.substring("gauge:".length());
            dataSeriesList = Lists.newArrayList();
            for (String agentRollupId : request.agentRollupIds()) {
                // FIXME, rollup level 2 is nice since 30 min intervals
                // but need level 3 for long time periods
                int rollupLevel = 2;
                dataSeriesList.add(getDataSeriesForGauge(agentRollupId, gaugeName, from, to,
                        rollupLevel, rollupCaptureTimeFn, request.rollup(), timeZone, gapMillis));
            }
        } else {
            throw new IllegalStateException("Unexpected metric id: " + metricId);
        }

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private List<DataSeries> getTransactionReport(ReportRequest request, TimeZone timeZone,
            Date from, Date to, RollupCaptureTimeFn rollupCaptureTimeFn, double gapMillis,
            String metricId) throws Exception {
        TransactionQuery query = ImmutableTransactionQuery.builder()
                .transactionType(checkNotNull(request.transactionType()))
                // + 1 to make from non-inclusive, since data points are displayed as midpoint of
                // time range
                .from(from.getTime() + 1)
                .to(to.getTime())
                .rollupLevel(2) // FIXME, level 2 is nice since 30 min intervals
                                // but need level 3 for long time periods
                .build();
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (String agentRollupId : request.agentRollupIds()) {
            if (metricId.equals(RESPONSE_TIME_AVG)) {
                dataSeriesList.add(getDataSeriesForAverage(agentRollupId, query,
                        rollupCaptureTimeFn, request.rollup(), timeZone, gapMillis));
            } else if (metricId.equals(RESPONSE_TIME_PERCENTILE)) {
                dataSeriesList.add(getDataSeriesForPercentile(agentRollupId, query,
                        checkNotNull(request.metricPercentile()), rollupCaptureTimeFn,
                        request.rollup(), timeZone, gapMillis));
            } else if (metricId.equals(THROUGHPUT)) {
                dataSeriesList.add(getDataSeriesForThroughput(agentRollupId, query,
                        rollupCaptureTimeFn, request.rollup(), timeZone, gapMillis));
            } else {
                throw new IllegalStateException("Unexpected metric id: " + metricId);
            }
        }
        return dataSeriesList;
    }

    private DataSeries getDataSeriesForAverage(String agentRollupId, TransactionQuery query,
            RollupCaptureTimeFn rollupCaptureTimeFn, ROLLUP rollup,
            TimeZone timeZone, double gapMillis) throws Exception {

        DataSeries dataSeries =
                new DataSeries(agentRepository.readAgentRollupDisplay(agentRollupId));
        List<OverviewAggregate> aggregates =
                aggregateRepository.readOverviewAggregates(agentRollupId, query);
        aggregates =
                TransactionCommonService.rollUpOverviewAggregates(aggregates, rollupCaptureTimeFn);
        if (aggregates.isEmpty()) {
            return dataSeries;
        }
        OverviewAggregate lastAggregate = aggregates.get(aggregates.size() - 1);
        long lastCaptureTime = lastAggregate.captureTime();
        long lastRollupCaptureTime = rollupCaptureTimeFn.apply(lastCaptureTime);
        if (lastCaptureTime != lastRollupCaptureTime) {
            aggregates.set(aggregates.size() - 1, ImmutableOverviewAggregate.builder()
                    .copyFrom(lastAggregate)
                    .captureTime(lastRollupCaptureTime)
                    .build());
        }
        OverviewAggregate priorAggregate = null;
        for (OverviewAggregate aggregate : aggregates) {
            if (priorAggregate != null
                    && aggregate.captureTime() - priorAggregate.captureTime() > gapMillis) {
                dataSeries.addNull();
            }
            dataSeries.add(getIntervalAverage(rollup, timeZone, aggregate.captureTime()),
                    aggregate.totalDurationNanos()
                            / (aggregate.transactionCount() * NANOSECONDS_PER_MILLISECOND));
            priorAggregate = aggregate;
        }
        double totalDurationNanos = 0;
        long transactionCount = 0;
        for (OverviewAggregate aggregate : aggregates) {
            totalDurationNanos += aggregate.totalDurationNanos();
            transactionCount += aggregate.transactionCount();
        }
        dataSeries
                .setOverall(totalDurationNanos / (transactionCount * NANOSECONDS_PER_MILLISECOND));
        return dataSeries;
    }

    private DataSeries getDataSeriesForPercentile(String agentRollupId, TransactionQuery query,
            double percentile, RollupCaptureTimeFn rollupCaptureTimeFn, ROLLUP rollup,
            TimeZone timeZone, double gapMillis) throws Exception {
        DataSeries dataSeries =
                new DataSeries(agentRepository.readAgentRollupDisplay(agentRollupId));
        List<PercentileAggregate> aggregates =
                aggregateRepository.readPercentileAggregates(agentRollupId, query);
        aggregates = TransactionCommonService.rollUpPercentileAggregates(aggregates,
                rollupCaptureTimeFn);
        if (aggregates.isEmpty()) {
            return dataSeries;
        }
        PercentileAggregate lastAggregate = aggregates.get(aggregates.size() - 1);
        long lastCaptureTime = lastAggregate.captureTime();
        long lastRollupCaptureTime = rollupCaptureTimeFn.apply(lastCaptureTime);
        if (lastCaptureTime != lastRollupCaptureTime) {
            aggregates.set(aggregates.size() - 1, ImmutablePercentileAggregate.builder()
                    .copyFrom(lastAggregate)
                    .captureTime(lastRollupCaptureTime)
                    .build());
        }
        PercentileAggregate priorAggregate = null;
        for (PercentileAggregate aggregate : aggregates) {
            if (priorAggregate != null
                    && aggregate.captureTime() - priorAggregate.captureTime() > gapMillis) {
                dataSeries.addNull();
            }
            LazyHistogram durationNanosHistogram =
                    new LazyHistogram(aggregate.durationNanosHistogram());
            dataSeries.add(getIntervalAverage(rollup, timeZone, aggregate.captureTime()),
                    durationNanosHistogram.getValueAtPercentile(percentile)
                            / NANOSECONDS_PER_MILLISECOND);
            priorAggregate = aggregate;
        }
        LazyHistogram mergedHistogram = new LazyHistogram();
        for (PercentileAggregate aggregate : aggregates) {
            mergedHistogram.merge(aggregate.durationNanosHistogram());
        }
        dataSeries.setOverall(
                mergedHistogram.getValueAtPercentile(percentile) / NANOSECONDS_PER_MILLISECOND);
        return dataSeries;
    }

    private DataSeries getDataSeriesForThroughput(String agentRollupId, TransactionQuery query,
            RollupCaptureTimeFn rollupCaptureTimeFn, ROLLUP rollup, TimeZone timeZone,
            double gapMillis) throws Exception {
        DataSeries dataSeries =
                new DataSeries(agentRepository.readAgentRollupDisplay(agentRollupId));
        List<ThroughputAggregate> aggregates =
                aggregateRepository.readThroughputAggregates(agentRollupId, query);
        aggregates = TransactionCommonService.rollUpThroughputAggregates(aggregates,
                rollupCaptureTimeFn);
        if (aggregates.isEmpty()) {
            return dataSeries;
        }
        ThroughputAggregate lastAggregate = aggregates.get(aggregates.size() - 1);
        long lastCaptureTime = lastAggregate.captureTime();
        long lastRollupCaptureTime = rollupCaptureTimeFn.apply(lastCaptureTime);
        if (lastCaptureTime != lastRollupCaptureTime) {
            aggregates.set(aggregates.size() - 1, ImmutableThroughputAggregate.builder()
                    .copyFrom(lastAggregate)
                    .captureTime(lastRollupCaptureTime)
                    .build());
        }
        long transactionCount = 0;
        long totalIntervalMillis = 0;
        ThroughputAggregate priorAggregate = null;
        for (ThroughputAggregate aggregate : aggregates) {
            if (priorAggregate != null
                    && aggregate.captureTime() - priorAggregate.captureTime() > gapMillis) {
                dataSeries.addNull();
            }
            long rollupIntervalMillis =
                    getRollupIntervalMillis(rollup, timeZone, aggregate.captureTime());
            dataSeries.add(getIntervalAverage(rollup, timeZone, aggregate.captureTime()),
                    60000.0 * aggregate.transactionCount() / rollupIntervalMillis);
            transactionCount += aggregate.transactionCount();
            totalIntervalMillis += rollupIntervalMillis;
            priorAggregate = aggregate;
        }
        dataSeries.setOverall(60000.0 * transactionCount / totalIntervalMillis);
        return dataSeries;
    }

    private DataSeries getDataSeriesForGauge(String agentRollupId, String gaugeName, Date from,
            Date to, int rollupLevel, RollupCaptureTimeFn rollupCaptureTimeFn, ROLLUP rollup,
            TimeZone timeZone, double gapMillis) throws Exception {
        DataSeries dataSeries =
                new DataSeries(agentRepository.readAgentRollupDisplay(agentRollupId));
        // from + 1 to make from non-inclusive, since data points are displayed as midpoint of
        // time range
        List<GaugeValue> gaugeValues = gaugeValueRepository.readGaugeValues(agentRollupId,
                gaugeName, from.getTime() + 1, to.getTime(), rollupLevel);
        gaugeValues = GaugeValueJsonService.rollUpGaugeValues(gaugeValues, gaugeName,
                rollupCaptureTimeFn);
        if (gaugeValues.isEmpty()) {
            return dataSeries;
        }
        GaugeValue lastGaugeValue = gaugeValues.get(gaugeValues.size() - 1);
        long lastCaptureTime = lastGaugeValue.getCaptureTime();
        long lastRollupCaptureTime = rollupCaptureTimeFn.apply(lastCaptureTime);
        if (lastCaptureTime != lastRollupCaptureTime) {
            gaugeValues.set(gaugeValues.size() - 1, lastGaugeValue.toBuilder()
                    .setCaptureTime(lastRollupCaptureTime)
                    .build());
        }
        GaugeValue priorGaugeValue = null;
        for (GaugeValue gaugeValue : gaugeValues) {
            if (priorGaugeValue != null
                    && gaugeValue.getCaptureTime() - priorGaugeValue.getCaptureTime() > gapMillis) {
                dataSeries.addNull();
            }
            dataSeries.add(getIntervalAverage(rollup, timeZone, gaugeValue.getCaptureTime()),
                    gaugeValue.getValue());
            priorGaugeValue = gaugeValue;
        }
        double total = 0;
        long weight = 0;
        for (GaugeValue gaugeValue : gaugeValues) {
            total += gaugeValue.getValue() * gaugeValue.getWeight();
            weight += gaugeValue.getWeight();
        }
        dataSeries.setOverall(total / weight);
        return dataSeries;
    }

    private static void checkPermissions(List<String> agentRollupIds, String permission,
            Authentication authentication) {
        for (String agentRollupId : agentRollupIds) {
            if (!authentication.isAgentPermitted(agentRollupId, permission)) {
                throw new JsonServiceException(HttpResponseStatus.FORBIDDEN);
            }
        }
    }

    private static long getIntervalAverage(ROLLUP rollup, TimeZone timeZone, long captureTime) {
        return captureTime - getRollupIntervalMillis(rollup, timeZone, captureTime) / 2;
    }

    @VisibleForTesting
    static long getRollupIntervalMillis(ROLLUP rollup, TimeZone timeZone, long captureTime) {
        Calendar calendar;
        switch (rollup) {
            case HOURLY:
                return HOURS.toMillis(1);
            case DAILY:
                calendar = Calendar.getInstance(timeZone);
                calendar.setTimeInMillis(captureTime);
                calendar.add(Calendar.DATE, -1);
                return captureTime - calendar.getTimeInMillis();
            case WEEKLY:
                calendar = Calendar.getInstance(timeZone);
                calendar.setTimeInMillis(captureTime);
                calendar.add(Calendar.DATE, -7);
                return captureTime - calendar.getTimeInMillis();
            case MONTHLY:
                calendar = Calendar.getInstance(timeZone);
                calendar.setTimeInMillis(captureTime);
                calendar.add(Calendar.MONTH, -1);
                return captureTime - calendar.getTimeInMillis();
            default:
                throw new IllegalStateException("Unexpected rollup: " + rollup);
        }
    }

    @Value.Immutable
    interface RequestWithAgentRollupIds {
        List<String> agentRollupIds();
    }

    @Value.Immutable
    interface ReportRequest {
        List<String> agentRollupIds();
        String metricId();
        @Nullable
        Double metricPercentile();
        @Nullable
        String transactionType();
        String fromDate();
        String toDate();
        ROLLUP rollup();
        String timeZoneId();
    }

    enum ROLLUP {
        HOURLY, DAILY, WEEKLY, MONTHLY
    }

    @VisibleForTesting
    static class RollupCaptureTimeFn implements Function<Long, Long> {

        private final ROLLUP rollup;
        private final TimeZone timeZone;
        private final int baseDayOfWeek;

        @VisibleForTesting
        RollupCaptureTimeFn(ROLLUP rollup, TimeZone timeZone, String baseCaptureTime)
                throws ParseException {
            this.rollup = rollup;
            this.timeZone = timeZone;
            if (rollup == ROLLUP.WEEKLY) {
                // SimpleDateFormat and Calendar both need to be in same timezone
                // (in this case default)
                SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(simpleDateFormat.parse(baseCaptureTime));
                baseDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
            } else {
                baseDayOfWeek = -1;
            }
        }

        @Override
        public Long apply(Long captureTime) {
            Calendar calendar;
            switch (rollup) {
                case HOURLY:
                    return Utils.getRollupCaptureTime(captureTime, HOURS.toMillis(1), timeZone);
                case DAILY:
                    return getDailyRollupCaptureTime(captureTime).getTimeInMillis();
                case WEEKLY:
                    calendar = getDailyRollupCaptureTime(captureTime);
                    int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
                    int diff = baseDayOfWeek - dayOfWeek;
                    if (diff < 0) {
                        diff += 7;
                    }
                    calendar.add(Calendar.DATE, diff);
                    return calendar.getTimeInMillis();
                case MONTHLY:
                    calendar = Calendar.getInstance(timeZone);
                    calendar.setTimeInMillis(captureTime);
                    calendar.set(Calendar.DAY_OF_MONTH, 1);
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    if (calendar.getTimeInMillis() == captureTime) {
                        return captureTime;
                    } else {
                        calendar.add(Calendar.MONTH, 1);
                        return calendar.getTimeInMillis();
                    }
                default:
                    throw new IllegalStateException("Unexpected rollup: " + rollup);
            }
        }

        private Calendar getDailyRollupCaptureTime(Long captureTime) {
            Calendar calendar;
            calendar = Calendar.getInstance(timeZone);
            calendar.setTimeInMillis(captureTime);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            if (calendar.getTimeInMillis() == captureTime) {
                return calendar;
            } else {
                calendar.add(Calendar.DATE, 1);
                return calendar;
            }
        }
    }
}
