/*
 * Copyright 2013-2023 the original author or authors.
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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;

import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.config.MoreConfigDefaults;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ErrorIntervalCollector;
import org.glowroot.common2.repo.ImmutableSyntheticResult;
import org.glowroot.common2.repo.SyntheticResult;
import org.glowroot.common2.repo.SyntheticResult.ErrorInterval;
import org.glowroot.common2.repo.SyntheticResultRepository;
import org.glowroot.common2.repo.util.RollupLevelService;
import org.glowroot.common2.repo.util.RollupLevelService.DataKind;
import org.glowroot.ui.ChartMarking.ChartMarkingInterval;
import org.glowroot.ui.MultiErrorIntervalCollector.MultiErrorInterval;
import org.glowroot.ui.MultiErrorIntervalMerger.GroupedMultiErrorInterval;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class SyntheticResultJsonService {

    private static final double NANOSECONDS_PER_MILLISECOND = 1000000.0;

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final SyntheticResultRepository syntheticResultRepository;
    private final RollupLevelService rollupLevelService;
    private final ConfigRepository configRepository;
    private final Clock clock;

    SyntheticResultJsonService(SyntheticResultRepository syntheticResultRepository,
            RollupLevelService rollupLevelService, ConfigRepository configRepository, Clock clock) {
        this.syntheticResultRepository = syntheticResultRepository;
        this.rollupLevelService = rollupLevelService;
        this.configRepository = configRepository;
        this.clock = clock;
    }

    @GET(path = "/backend/synthetic-monitor/results", permission = "agent:syntheticMonitor")
    String getSyntheticResults(@BindAgentRollupId String agentRollupId,
            @BindRequest SyntheticResultRequest request) throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(request.from(), request.to(),
                DataKind.GENERAL);
        long dataPointIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        double gapMillis = dataPointIntervalMillis * 1.5;
        long revisedFrom = request.from() - dataPointIntervalMillis;
        long revisedTo = request.to() + dataPointIntervalMillis;

        List<SyntheticMonitor> allSyntheticMonitors =
                getAllSyntheticMonitors(agentRollupId, request.from(), request.to());
        List<String> syntheticMonitorIds;
        if (request.all()) {
            syntheticMonitorIds = Lists.newArrayList();
            for (SyntheticMonitor syntheticMonitor : allSyntheticMonitors) {
                syntheticMonitorIds.add(syntheticMonitor.id());
            }
        } else {
            syntheticMonitorIds = request.syntheticMonitorId();
        }
        Map<String, List<SyntheticResult>> map = Maps.newLinkedHashMap();
        for (String syntheticMonitorId : syntheticMonitorIds) {
            map.put(syntheticMonitorId, getSyntheticResults(agentRollupId, revisedFrom, revisedTo,
                    syntheticMonitorId, rollupLevel));
        }
        if (rollupLevel != 0) {
            syncManualRollupCaptureTimes(map, rollupLevel);
        }
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        List<Map<Long, Long>> executionCountsList = Lists.newArrayList();
        List<List<MultiErrorInterval>> multiErrorIntervalsList = Lists.newArrayList();
        for (Map.Entry<String, List<SyntheticResult>> entry : map.entrySet()) {
            String syntheticMonitorId = entry.getKey();
            List<SyntheticResult> syntheticResults = entry.getValue();
            dataSeriesList.add(
                    convertToDataSeriesWithGaps(syntheticMonitorId, syntheticResults, gapMillis));
            Map<Long, Long> executionCounts = Maps.newHashMap();
            executionCountsList.add(executionCounts);
            for (SyntheticResult syntheticResult : syntheticResults) {
                executionCounts.put(syntheticResult.captureTime(),
                        syntheticResult.executionCount());
            }
            ErrorIntervalCollector errorIntervalCollector = new ErrorIntervalCollector();
            for (SyntheticResult syntheticResult : syntheticResults) {
                List<ErrorInterval> errorIntervals = syntheticResult.errorIntervals();
                if (errorIntervals.isEmpty()) {
                    errorIntervalCollector.addGap();
                } else {
                    errorIntervalCollector.addErrorIntervals(errorIntervals);
                }
            }
            MultiErrorIntervalCollector multiErrorIntervalCollector =
                    new MultiErrorIntervalCollector();
            multiErrorIntervalCollector
                    .addErrorIntervals(errorIntervalCollector.getMergedErrorIntervals());
            multiErrorIntervalsList.add(multiErrorIntervalCollector.getMergedMultiErrorIntervals());
        }
        List<ChartMarking> markings = toChartMarkings(multiErrorIntervalsList, syntheticMonitorIds);
        boolean displayNoSyntheticMonitorsConfigured;
        if (allSyntheticMonitors.isEmpty()) {
            displayNoSyntheticMonitorsConfigured =
                    configRepository.getSyntheticMonitorConfigs(agentRollupId).isEmpty();
        } else {
            displayNoSyntheticMonitorsConfigured = false;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeObjectField("dataSeries", dataSeriesList);
            jg.writeNumberField("dataPointIntervalMillis", dataPointIntervalMillis);
            jg.writeObjectField("executionCounts", executionCountsList);
            jg.writeObjectField("markings", markings);
            jg.writeObjectField("allSyntheticMonitors", allSyntheticMonitors);
            jg.writeBooleanField("displayNoSyntheticMonitorsConfigured",
                    displayNoSyntheticMonitorsConfigured);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    private ImmutableList<SyntheticMonitor> getAllSyntheticMonitors(String agentRollupId,
            long from, long to) throws Exception {
        Map<String, String> syntheticMonitorIds =
                syntheticResultRepository.getSyntheticMonitorIds(agentRollupId, from, to);
        Multiset<String> multiset = HashMultiset.create();
        multiset.addAll(syntheticMonitorIds.values());
        List<SyntheticMonitor> syntheticMonitors = Lists.newArrayList();
        for (Map.Entry<String, String> entry : syntheticMonitorIds.entrySet()) {
            String id = entry.getKey();
            String display = entry.getValue();
            if (multiset.count(entry.getValue()) > 1) {
                display += " (" + id + ")";
            }
            syntheticMonitors.add(ImmutableSyntheticMonitor.of(id, display));
        }
        if (to > clock.currentTimeMillis()) {
            // so that new synthetic monitors will show up right away
            List<SyntheticMonitorConfig> configs =
                    configRepository.getSyntheticMonitorConfigs(agentRollupId);
            for (SyntheticMonitorConfig config : configs) {
                if (!syntheticMonitorIds.containsKey(config.getId())) {
                    syntheticMonitors.add(ImmutableSyntheticMonitor.of(config.getId(),
                            MoreConfigDefaults.getDisplayOrDefault(config)));
                }
            }
        }
        return new SyntheticMonitorOrdering().immutableSortedCopy(syntheticMonitors);
    }

    private List<SyntheticResult> getSyntheticResults(String agentRollupId, long from, long to,
            String syntheticMonitorId, int rollupLevel) throws Exception {
        List<SyntheticResult> syntheticResults = syntheticResultRepository
                .readSyntheticResults(agentRollupId, syntheticMonitorId, from, to, rollupLevel);
        if (rollupLevel == 0) {
            return syntheticResults;
        }
        long nonRolledUpFrom = from;
        if (!syntheticResults.isEmpty()) {
            nonRolledUpFrom = Iterables.getLast(syntheticResults).captureTime() + 1;
        }
        List<SyntheticResult> orderedNonRolledUpSyntheticResults = Lists.newArrayList();
        orderedNonRolledUpSyntheticResults
                .addAll(syntheticResultRepository.readSyntheticResults(agentRollupId,
                        syntheticMonitorId, nonRolledUpFrom, to, 0));
        syntheticResults = Lists.newArrayList(syntheticResults);
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        syntheticResults.addAll(rollUpSyntheticResults(orderedNonRolledUpSyntheticResults,
                new RollupCaptureTimeFn(fixedIntervalMillis)));
        return syntheticResults;
    }

    private <K> void syncManualRollupCaptureTimes(Map<K, List<SyntheticResult>> map,
            int rollupLevel) {
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel).intervalMillis();
        Map<K, Long> manualRollupCaptureTimes = Maps.newHashMap();
        long maxCaptureTime = Long.MIN_VALUE;
        for (Map.Entry<K, List<SyntheticResult>> entry : map.entrySet()) {
            List<SyntheticResult> syntheticResults = entry.getValue();
            if (syntheticResults.isEmpty()) {
                continue;
            }
            SyntheticResult lastSyntheticResult = Iterables.getLast(syntheticResults);
            long lastCaptureTime = lastSyntheticResult.captureTime();
            maxCaptureTime = Math.max(maxCaptureTime, lastCaptureTime);
            if (lastCaptureTime % fixedIntervalMillis != 0) {
                manualRollupCaptureTimes.put(entry.getKey(), lastCaptureTime);
            }
        }
        if (maxCaptureTime == Long.MIN_VALUE) {
            // nothing to sync
            return;
        }
        long maxRollupCaptureTime = CaptureTimes.getRollup(maxCaptureTime, fixedIntervalMillis);
        long maxDiffToSync = Math.min(fixedIntervalMillis / 5, 60000);
        for (Map.Entry<K, Long> entry : manualRollupCaptureTimes.entrySet()) {
            Long captureTime = entry.getValue();
            if (CaptureTimes.getRollup(captureTime,
                    fixedIntervalMillis) != maxRollupCaptureTime) {
                continue;
            }
            if (maxCaptureTime - captureTime > maxDiffToSync) {
                // only sync up times that are close to each other
                continue;
            }
            K key = entry.getKey();
            List<SyntheticResult> syntheticResults = checkNotNull(map.get(key));
            // make copy in case ImmutableList
            syntheticResults = Lists.newArrayList(syntheticResults);
            SyntheticResult lastSyntheticResult = Iterables.getLast(syntheticResults);
            syntheticResults.set(syntheticResults.size() - 1, ImmutableSyntheticResult.builder()
                    .copyFrom(lastSyntheticResult)
                    .captureTime(maxCaptureTime)
                    .build());
            map.put(key, syntheticResults);
        }
    }

    private static List<SyntheticResult> rollUpSyntheticResults(
            List<SyntheticResult> orderedNonRolledUpSyntheticResults,
            Function<Long, Long> rollupCaptureTimeFn) {
        List<SyntheticResult> rolledUpSyntheticResults = Lists.newArrayList();
        double totalDurationNanos = 0;
        long executionCount = 0;
        ErrorIntervalCollector errorIntervalCollector = new ErrorIntervalCollector();
        long currRollupCaptureTime = Long.MIN_VALUE;
        for (SyntheticResult nonRolledUpSyntheticResult : orderedNonRolledUpSyntheticResults) {
            long captureTime = nonRolledUpSyntheticResult.captureTime();
            long rollupCaptureTime = rollupCaptureTimeFn.apply(captureTime);
            if (rollupCaptureTime != currRollupCaptureTime && executionCount > 0) {
                rolledUpSyntheticResults.add(ImmutableSyntheticResult.builder()
                        .captureTime(currRollupCaptureTime)
                        .totalDurationNanos(totalDurationNanos)
                        .executionCount(executionCount)
                        .errorIntervals(errorIntervalCollector.getMergedErrorIntervals())
                        .build());
                totalDurationNanos = 0;
                executionCount = 0;
                errorIntervalCollector = new ErrorIntervalCollector();
            }
            currRollupCaptureTime = rollupCaptureTime;
            totalDurationNanos += nonRolledUpSyntheticResult.totalDurationNanos();
            executionCount += nonRolledUpSyntheticResult.executionCount();
            List<ErrorInterval> errorIntervals = nonRolledUpSyntheticResult.errorIntervals();
            if (errorIntervals.isEmpty()) {
                errorIntervalCollector.addGap();
            } else {
                errorIntervalCollector.addErrorIntervals(errorIntervals);
            }
        }
        if (executionCount > 0) {
            // roll up final one
            long lastCaptureTime =
                    Iterables.getLast(orderedNonRolledUpSyntheticResults).captureTime();
            rolledUpSyntheticResults.add(ImmutableSyntheticResult.builder()
                    .captureTime(lastCaptureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .executionCount(executionCount)
                    .errorIntervals(errorIntervalCollector.getMergedErrorIntervals())
                    .build());
        }
        return rolledUpSyntheticResults;
    }

    private static DataSeries convertToDataSeriesWithGaps(String dataSeriesName,
            List<SyntheticResult> syntheticResults, double gapMillis) {
        DataSeries dataSeries = new DataSeries(dataSeriesName);
        SyntheticResult lastSyntheticResult = null;
        for (SyntheticResult syntheticResult : syntheticResults) {
            if (syntheticResult.executionCount() == 0) {
                // only errors captured in this aggregate
                continue;
            }
            if (lastSyntheticResult != null && syntheticResult.captureTime()
                    - lastSyntheticResult.captureTime() > gapMillis) {
                dataSeries.addNull();
            }
            dataSeries.add(syntheticResult.captureTime(),
                    (syntheticResult.totalDurationNanos() / syntheticResult.executionCount())
                            / NANOSECONDS_PER_MILLISECOND);
            lastSyntheticResult = syntheticResult;
        }
        return dataSeries;
    }

    private static List<ChartMarking> toChartMarkings(
            List<List<MultiErrorInterval>> multiErrorIntervalsList,
            List<String> syntheticMonitorIds) {
        MultiErrorIntervalMerger multiErrorIntervalMerger = new MultiErrorIntervalMerger();
        for (int i = 0; i < multiErrorIntervalsList.size(); i++) {
            List<MultiErrorInterval> multiErrorIntervals = multiErrorIntervalsList.get(i);
            String syntheticMonitorId = syntheticMonitorIds.get(i);
            multiErrorIntervalMerger.addMultiErrorIntervals(syntheticMonitorId,
                    multiErrorIntervals);
        }
        List<ChartMarking> chartMarkings = Lists.newArrayList();
        for (GroupedMultiErrorInterval groupedMultiErrorInterval : multiErrorIntervalMerger
                .getGroupedMultiErrorIntervals()) {
            ImmutableChartMarking.Builder chartMarking = ImmutableChartMarking.builder()
                    .from(groupedMultiErrorInterval.from())
                    .to(groupedMultiErrorInterval.to());
            for (Map.Entry<String, List<ErrorInterval>> entry : groupedMultiErrorInterval
                    .errorIntervals().entrySet()) {
                chartMarking.putIntervals(entry.getKey(),
                        toChartMarkingIntervals(entry.getValue()));
            }
            chartMarkings.add(chartMarking.build());
        }
        return chartMarkings;
    }

    private static List<ChartMarkingInterval> toChartMarkingIntervals(
            List<ErrorInterval> errorIntervals) {
        List<ChartMarkingInterval> chartMarkingIntervals =
                Lists.newArrayListWithCapacity(errorIntervals.size());
        for (ErrorInterval errorInterval : errorIntervals) {
            chartMarkingIntervals.add(ImmutableChartMarkingInterval.builder()
                    .from(errorInterval.from())
                    .to(errorInterval.to())
                    .count(errorInterval.count())
                    .message(errorInterval.message())
                    .build());
        }
        return chartMarkingIntervals;
    }

    @Value.Immutable
    abstract static class SyntheticResultRequest {
        abstract long from();
        abstract long to();
        @Value.Default
        boolean all() {
            return false;
        }
        // singular because this is used in query string
        abstract ImmutableList<String> syntheticMonitorId();
    }

    private static class SyntheticMonitorOrdering extends Ordering<SyntheticMonitor> {
        @Override
        public int compare(SyntheticMonitor left, SyntheticMonitor right) {
            return left.display().compareToIgnoreCase(right.display());
        }
    }

    private static class RollupCaptureTimeFn implements Function<Long, Long> {

        private final long fixedIntervalMillis;

        private RollupCaptureTimeFn(long fixedIntervalMillis) {
            this.fixedIntervalMillis = fixedIntervalMillis;
        }

        @Override
        public Long apply(Long captureTime) {
            return CaptureTimes.getRollup(captureTime, fixedIntervalMillis);
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface SyntheticMonitor {
        String id();
        String display();
    }
}
