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
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
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
import org.glowroot.common.repo.AgentRollupRepository;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.GaugeValueRepository.Gauge;
import org.glowroot.common.repo.TransactionTypeRepository;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.ui.GaugeValueJsonService.GaugeOrdering;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.ui.LayoutService.FilteredAgentRollup;
import org.glowroot.ui.LayoutService.Permissions;
import org.glowroot.ui.RoleConfigJsonService.FlattenedAgentRollup;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

@JsonService
class ReportJsonService {

    private static final double NANOSECONDS_PER_MILLISECOND = 1000000.0;

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AgentRollupRepository agentRollupRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final AggregateRepository aggregateRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final RollupLevelService rollupLevelService;

    ReportJsonService(AgentRollupRepository agentRollupRepository,
            TransactionTypeRepository transactionTypeRepository,
            AggregateRepository aggregateRepository, GaugeValueRepository gaugeValueRepository,
            RollupLevelService rollupLevelService) {
        this.agentRollupRepository = agentRollupRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.aggregateRepository = aggregateRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.rollupLevelService = rollupLevelService;
    }

    @GET(path = "/backend/report/agent-rollups", permission = "")
    String getAllAgentRollups(@BindRequest AgentRollupReportRequest request,
            @BindAuthentication Authentication authentication) throws Exception {
        TimeZone timeZone = TimeZone.getTimeZone(request.timeZoneId());
        FromToPair fromToPair = parseDates(request.fromDate(), request.toDate(), timeZone);
        Date from = fromToPair.from();
        Date to = fromToPair.to();
        List<FilteredAgentRollup> agentRollups = LayoutJsonService.filter(
                agentRollupRepository.readAgentRollups(from.getTime(), to.getTime()),
                authentication, new Predicate<Permissions>() {
                    @Override
                    public boolean apply(@Nullable Permissions permissions) {
                        return permissions != null && permissions.transaction().overview()
                                && permissions.error().overview() && permissions.jvm().gauges();
                    }
                });
        List<FlattenedAgentRollup> flattenedAgentRollups = Lists.newArrayList();
        for (FilteredAgentRollup agentRollup : agentRollups) {
            flattenedAgentRollups.addAll(getFlattenedAgentRollups(agentRollup, 0));
        }
        return mapper.writeValueAsString(flattenedAgentRollups);
    }

    // permission is checked based on agentRollupIds in the request
    @GET(path = "/backend/report/transaction-types-and-gauges", permission = "")
    String getAllGauges(@BindRequest TransactionTypesAndGaugesRequest request,
            @BindAuthentication Authentication authentication) throws Exception {
        checkPermissions(request.agentRollupIds(), "agent:transaction:overview", authentication);
        checkPermissions(request.agentRollupIds(), "agent:jvm:gauges", authentication);

        TimeZone timeZone = TimeZone.getTimeZone(request.timeZoneId());
        FromToPair fromToPair = parseDates(request.fromDate(), request.toDate(), timeZone);
        Date from = fromToPair.from();
        Date to = fromToPair.to();

        Map<String, List<String>> transactionTypesMap = transactionTypeRepository.read();
        Set<String> transactionTypes = Sets.newHashSet();
        Set<Gauge> gauges = Sets.newHashSet();
        for (String agentRollupId : request.agentRollupIds()) {
            List<String> transactionTypesForAgentRollupId = transactionTypesMap.get(agentRollupId);
            if (transactionTypesForAgentRollupId != null) {
                transactionTypes.addAll(transactionTypesForAgentRollupId);
            }
            gauges.addAll(
                    gaugeValueRepository.getGauges(agentRollupId, from.getTime(), to.getTime()));
        }
        return mapper.writeValueAsString(ImmutableTransactionTypesAndGaugesReponse.builder()
                .addAllTransactionTypes(Ordering.natural().sortedCopy(transactionTypes))
                .addAllGauges(new GaugeOrdering().sortedCopy(gauges))
                .build());
    }

    // permission is checked based on agentRollupIds in the request
    @GET(path = "/backend/report", permission = "")
    String getReport(@BindRequest ReportRequest request,
            @BindAuthentication Authentication authentication) throws Exception {
        String metric = request.metric();
        if (metric.startsWith("transaction:")) {
            checkPermissions(request.agentRollupIds(), "agent:transaction:overview",
                    authentication);
        } else if (metric.startsWith("error:")) {
            checkPermissions(request.agentRollupIds(), "agent:error:overview", authentication);
        } else if (metric.startsWith("gauge:")) {
            checkPermissions(request.agentRollupIds(), "agent:jvm:gauges", authentication);
        } else {
            throw new IllegalStateException("Unexpected metric: " + metric);
        }
        TimeZone timeZone = TimeZone.getTimeZone(request.timeZoneId());
        FromToPair fromToPair = parseDates(request.fromDate(), request.toDate(), timeZone);
        Date from = fromToPair.from();
        Date to = fromToPair.to();

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
        if (metric.startsWith("transaction:") || metric.startsWith("error:")) {
            dataSeriesList = getTransactionReport(request, timeZone, from, to, rollupCaptureTimeFn,
                    gapMillis);
        } else if (metric.startsWith("gauge:")) {
            String gaugeName = metric.substring("gauge:".length());
            dataSeriesList = Lists.newArrayList();
            for (String agentRollupId : request.agentRollupIds()) {
                int rollupLevel = rollupLevelService.getGaugeRollupLevelForReport(from.getTime());
                // level 3 (30 min intervals) is the minimum level needed
                rollupLevel = Math.max(rollupLevel, 3);
                if (rollupLevel == 4) {
                    verifyFourHourAggregateTimeZone(timeZone);
                }
                dataSeriesList.add(getDataSeriesForGauge(agentRollupId, gaugeName, from, to,
                        rollupLevel, rollupCaptureTimeFn, request.rollup(), timeZone, gapMillis));
            }
        } else {
            throw new IllegalStateException("Unexpected metric: " + metric);
        }

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeObjectField("dataSeries", dataSeriesList);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    private static FromToPair parseDates(String fromDate, String toDate, TimeZone timeZone)
            throws ParseException {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");
        simpleDateFormat.setTimeZone(timeZone);
        Date from = simpleDateFormat.parse(fromDate);
        Date to = simpleDateFormat.parse(toDate);
        Calendar cal = Calendar.getInstance();
        cal.setTime(to);
        cal.add(Calendar.DATE, 1);
        to = cal.getTime();
        return ImmutableFromToPair.builder()
                .from(from)
                .to(to)
                .build();
    }

    private static List<FlattenedAgentRollup> getFlattenedAgentRollups(
            FilteredAgentRollup agentRollup, int depth) {
        List<FlattenedAgentRollup> flattenedAgentRollups = Lists.newArrayList();
        flattenedAgentRollups.add(ImmutableFlattenedAgentRollup.builder()
                .id(agentRollup.id())
                .display(agentRollup.display())
                .lastDisplayPart(agentRollup.lastDisplayPart())
                .depth(depth)
                .build());
        for (FilteredAgentRollup childAgentRollup : agentRollup.children()) {
            flattenedAgentRollups.addAll(getFlattenedAgentRollups(childAgentRollup, depth + 1));
        }
        return flattenedAgentRollups;
    }

    private List<DataSeries> getTransactionReport(ReportRequest request, TimeZone timeZone,
            Date from, Date to, RollupCaptureTimeFn rollupCaptureTimeFn, double gapMillis)
            throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForReport(from.getTime());
        // level 2 (30 min intervals) is the minimum level needed
        rollupLevel = Math.max(rollupLevel, 2);
        if (rollupLevel == 3) {
            verifyFourHourAggregateTimeZone(timeZone);
        }
        TransactionQuery query = ImmutableTransactionQuery.builder()
                .transactionType(checkNotNull(request.transactionType()))
                .transactionName(Strings.emptyToNull(checkNotNull(request.transactionName())))
                // + 1 to make from non-inclusive, since data points are displayed as midpoint of
                // time range
                .from(from.getTime() + 1)
                .to(to.getTime())
                .rollupLevel(rollupLevel)
                .build();
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        String metric = request.metric();
        for (String agentRollupId : request.agentRollupIds()) {
            if (metric.equals("transaction:average")) {
                dataSeriesList.add(getDataSeriesForAverage(agentRollupId, query,
                        rollupCaptureTimeFn, request.rollup(), timeZone, gapMillis));
            } else if (metric.equals("transaction:x-percentile")) {
                dataSeriesList.add(getDataSeriesForPercentile(agentRollupId, query,
                        checkNotNull(request.percentile()), rollupCaptureTimeFn,
                        request.rollup(), timeZone, gapMillis));
            } else if (metric.equals("transaction:count")) {
                dataSeriesList.add(getDataSeriesForThroughput(agentRollupId, query,
                        rollupCaptureTimeFn, request.rollup(), timeZone, gapMillis,
                        new CountCalculator()));
            } else if (metric.equals("error:rate")) {
                dataSeriesList.add(getDataSeriesForThroughput(agentRollupId, query,
                        rollupCaptureTimeFn, request.rollup(), timeZone, gapMillis,
                        new ErrorRateCalculator()));
            } else if (metric.equals("error:count")) {
                dataSeriesList.add(getDataSeriesForThroughput(agentRollupId, query,
                        rollupCaptureTimeFn, request.rollup(), timeZone, gapMillis,
                        new ErrorCountCalculator()));
            } else {
                throw new IllegalStateException("Unexpected metric: " + metric);
            }
        }
        return dataSeriesList;
    }

    private DataSeries getDataSeriesForAverage(String agentRollupId, TransactionQuery query,
            RollupCaptureTimeFn rollupCaptureTimeFn, ROLLUP rollup,
            TimeZone timeZone, double gapMillis) throws Exception {

        DataSeries dataSeries =
                new DataSeries(agentRollupRepository.readAgentRollupDisplay(agentRollupId));
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
        // individual aggregate transaction counts cannot be zero, and aggregates is non-empty
        // (see above conditional), so transactionCount is guaranteed non-zero
        checkState(transactionCount != 0);
        dataSeries
                .setOverall(totalDurationNanos / (transactionCount * NANOSECONDS_PER_MILLISECOND));
        return dataSeries;
    }

    private DataSeries getDataSeriesForPercentile(String agentRollupId, TransactionQuery query,
            double percentile, RollupCaptureTimeFn rollupCaptureTimeFn, ROLLUP rollup,
            TimeZone timeZone, double gapMillis) throws Exception {
        DataSeries dataSeries =
                new DataSeries(agentRollupRepository.readAgentRollupDisplay(agentRollupId));
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
            double gapMillis, ThroughputAggregateFn throughputAggregateFn) throws Exception {
        DataSeries dataSeries =
                new DataSeries(agentRollupRepository.readAgentRollupDisplay(agentRollupId));
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
        ThroughputAggregate priorAggregate = null;
        for (ThroughputAggregate aggregate : aggregates) {
            long rollupIntervalMillis =
                    getRollupIntervalMillis(rollup, timeZone, aggregate.captureTime());
            Double value = throughputAggregateFn.getValue(aggregate, rollupIntervalMillis);
            if (value == null) {
                continue;
            }
            if (priorAggregate != null
                    && aggregate.captureTime() - priorAggregate.captureTime() > gapMillis) {
                dataSeries.addNull();
            }
            dataSeries.add(getIntervalAverage(rollup, timeZone, aggregate.captureTime()), value);
            priorAggregate = aggregate;
        }
        Double overall = throughputAggregateFn.getOverall();
        if (overall != null) {
            dataSeries.setOverall(overall);
        }
        return dataSeries;
    }

    private DataSeries getDataSeriesForGauge(String agentRollupId, String gaugeName, Date from,
            Date to, int rollupLevel, RollupCaptureTimeFn rollupCaptureTimeFn, ROLLUP rollup,
            TimeZone timeZone, double gapMillis) throws Exception {
        DataSeries dataSeries =
                new DataSeries(agentRollupRepository.readAgentRollupDisplay(agentRollupId));
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
        // individual gauge value weights cannot be zero, and gaugeValues is non-empty
        // (see above conditional), so weight is guaranteed non-zero
        checkState(weight != 0);
        dataSeries.setOverall(total / weight);
        return dataSeries;
    }

    private static void checkPermissions(List<String> agentRollupIds, String permission,
            Authentication authentication) throws Exception {
        for (String agentRollupId : agentRollupIds) {
            if (!authentication.isPermittedForAgentRollup(agentRollupId, permission)) {
                throw new JsonServiceException(HttpResponseStatus.FORBIDDEN);
            }
        }
    }

    private static void verifyFourHourAggregateTimeZone(TimeZone timeZone) {
        boolean gmt = timeZone.getID().equals("GMT") || timeZone.getID().startsWith("GMT-")
                || timeZone.getID().startsWith("GMT+");
        if (!gmt || timeZone.getRawOffset() % (4 * 3600000) != 0) {
            throw new IllegalStateException("The selected time zone is not supported because the"
                    + " time range exceeds the configured retention for 30-minute interval data and"
                    + " so 4-hour interval data must be used instead");
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
    interface AgentRollupReportRequest {
        String fromDate();
        String toDate();
        String timeZoneId();
    }

    @Value.Immutable
    interface TransactionTypesAndGaugesRequest {
        List<String> agentRollupIds();
        String fromDate();
        String toDate();
        String timeZoneId();
    }

    @Value.Immutable
    interface TransactionTypesAndGaugesReponse {
        List<String> transactionTypes();
        List<Gauge> gauges();
    }

    @Value.Immutable
    interface ReportRequest {
        List<String> agentRollupIds();

        String metric();
        @Nullable
        String transactionType();
        @Nullable
        String transactionName();
        @Nullable
        Double percentile();

        String fromDate();
        String toDate();
        ROLLUP rollup();
        String timeZoneId();
    }

    @Value.Immutable
    interface FromToPair {
        Date from();
        Date to();
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

    // the methods return null when the function depends on error_count and the data is from
    // glowroot central prior to 0.9.18 (when error_count was added to the
    // aggregate_*_throughput_rollup_* tables)
    private interface ThroughputAggregateFn {
        @Nullable
        Double getValue(ThroughputAggregate aggregate, long rollupIntervalMillis);
        @Nullable
        Double getOverall();
    }

    private static class CountCalculator implements ThroughputAggregateFn {

        private long transactionCount;

        @Override
        public @Nullable Double getValue(ThroughputAggregate aggregate, long rollupIntervalMillis) {
            transactionCount += aggregate.transactionCount();
            return (double) aggregate.transactionCount();
        }

        @Override
        public @Nullable Double getOverall() {
            return (double) transactionCount;
        }
    }

    private static class ErrorRateCalculator implements ThroughputAggregateFn {

        private boolean hasErrorRate;
        private long errorCount;
        private long transactionCount;

        @Override
        public @Nullable Double getValue(ThroughputAggregate aggregate, long rollupIntervalMillis) {
            Long errorCount = aggregate.errorCount();
            if (errorCount == null) {
                return null;
            }
            hasErrorRate = true;
            this.errorCount += errorCount;
            transactionCount += aggregate.transactionCount();
            return (100.0 * errorCount) / aggregate.transactionCount();
        }

        @Override
        public @Nullable Double getOverall() {
            return hasErrorRate ? (100.0 * errorCount) / transactionCount : null;
        }
    }

    private static class ErrorCountCalculator implements ThroughputAggregateFn {

        private boolean hasErrorCount;
        private long errorCount;

        @Override
        public @Nullable Double getValue(ThroughputAggregate aggregate, long rollupIntervalMillis) {
            Long errorCount = aggregate.errorCount();
            if (errorCount == null) {
                return null;
            }
            this.errorCount += errorCount;
            hasErrorCount = true;
            return errorCount.doubleValue();
        }

        @Override
        public @Nullable Double getOverall() {
            return hasErrorCount ? (double) errorCount : null;
        }
    }
}
