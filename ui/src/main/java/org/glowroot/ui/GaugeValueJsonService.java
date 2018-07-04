/*
 * Copyright 2013-2018 the original author or authors.
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
import java.util.Set;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;

import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.GaugeValueRepository;
import org.glowroot.common2.repo.GaugeValueRepository.Gauge;
import org.glowroot.common2.repo.ImmutableGauge;
import org.glowroot.common2.repo.util.RollupLevelService;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class GaugeValueJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final GaugeValueRepository gaugeValueRepository;
    private final RollupLevelService rollupLevelService;
    private final ConfigRepository configRepository;

    GaugeValueJsonService(GaugeValueRepository gaugeValueRepository,
            RollupLevelService rollupLevelService, ConfigRepository configRepository) {
        this.gaugeValueRepository = gaugeValueRepository;
        this.rollupLevelService = rollupLevelService;
        this.configRepository = configRepository;
    }

    @GET(path = "/backend/jvm/gauges", permission = "agent:jvm:gauges")
    String getGaugeValues(@BindAgentRollupId String agentRollupId,
            @BindRequest GaugeValueRequest request) throws Exception {
        int rollupLevel = rollupLevelService.getGaugeRollupLevelForView(request.from(),
                request.to(), agentRollupId.endsWith("::"));
        long dataPointIntervalMillis;
        if (rollupLevel == 0) {
            dataPointIntervalMillis = configRepository.getGaugeCollectionIntervalMillis();
        } else {
            dataPointIntervalMillis =
                    configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
        }
        Map<String, List<GaugeValue>> gaugeValues =
                getGaugeValues(agentRollupId, request, rollupLevel, dataPointIntervalMillis);
        if (isEmpty(gaugeValues)) {
            // fall back to largest aggregates in case expiration settings have recently changed
            rollupLevel = getLargestRollupLevel();
            dataPointIntervalMillis =
                    configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
            gaugeValues =
                    getGaugeValues(agentRollupId, request, rollupLevel, dataPointIntervalMillis);
        }
        if (rollupLevel != 0) {
            syncManualRollupCaptureTimes(gaugeValues, rollupLevel);
        }
        double gapMillis = dataPointIntervalMillis * 1.5;
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (Map.Entry<String, List<GaugeValue>> entry : gaugeValues.entrySet()) {
            dataSeriesList
                    .add(convertToDataSeriesWithGaps(entry.getKey(), entry.getValue(), gapMillis));
        }
        List<Gauge> gauges =
                gaugeValueRepository.getGauges(agentRollupId, request.from(), request.to());
        List<Gauge> sortedGauges = new GaugeOrdering().immutableSortedCopy(gauges);
        sortedGauges = addCounterSuffixesIfAndWhereNeeded(sortedGauges);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeObjectField("dataSeries", dataSeriesList);
            jg.writeNumberField("dataPointIntervalMillis", dataPointIntervalMillis);
            jg.writeObjectField("allGauges", sortedGauges);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    private Map<String, List<GaugeValue>> getGaugeValues(String agentRollupId,
            GaugeValueRequest request,
            int rollupLevel, long dataPointIntervalMillis) throws Exception {
        long revisedFrom = request.from() - dataPointIntervalMillis;
        long revisedTo = request.to() + dataPointIntervalMillis;
        Map<String, List<GaugeValue>> map = Maps.newLinkedHashMap();
        for (String gaugeName : request.gaugeName()) {
            List<GaugeValue> gaugeValues =
                    getGaugeValues(agentRollupId, revisedFrom, revisedTo, gaugeName, rollupLevel);
            map.put(gaugeName, gaugeValues);
        }
        return map;
    }

    private List<GaugeValue> getGaugeValues(String agentRollupId, long from, long to,
            String gaugeName, int rollupLevel) throws Exception {
        List<GaugeValue> gaugeValues = gaugeValueRepository.readGaugeValues(agentRollupId,
                gaugeName, from, to, rollupLevel);
        if (rollupLevel == 0) {
            return gaugeValues;
        }
        long nonRolledUpFrom = from;
        if (!gaugeValues.isEmpty()) {
            long lastRolledUpTime = Iterables.getLast(gaugeValues).getCaptureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<GaugeValue> orderedNonRolledUpGaugeValues = Lists.newArrayList();
        int lowestLevel = agentRollupId.endsWith("::") ? 1 : 0;
        orderedNonRolledUpGaugeValues.addAll(gaugeValueRepository.readGaugeValues(agentRollupId,
                gaugeName, nonRolledUpFrom, to, lowestLevel));
        gaugeValues = Lists.newArrayList(gaugeValues);
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
        gaugeValues.addAll(rollUpGaugeValues(orderedNonRolledUpGaugeValues, gaugeName,
                new RollupCaptureTimeFn(fixedIntervalMillis)));
        return gaugeValues;
    }

    private <K> void syncManualRollupCaptureTimes(Map<K, List<GaugeValue>> map, int rollupLevel) {
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
        Map<K, Long> manualRollupCaptureTimes = Maps.newHashMap();
        long maxCaptureTime = Long.MIN_VALUE;
        for (Map.Entry<K, List<GaugeValue>> entry : map.entrySet()) {
            List<GaugeValue> gaugeValues = entry.getValue();
            if (gaugeValues.isEmpty()) {
                continue;
            }
            long lastCaptureTime = Iterables.getLast(gaugeValues).getCaptureTime();
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
            List<GaugeValue> gaugeValues = checkNotNull(map.get(key));
            // make copy in case ImmutableList
            gaugeValues = Lists.newArrayList(gaugeValues);
            GaugeValue lastGaugeValue = Iterables.getLast(gaugeValues);
            gaugeValues.set(gaugeValues.size() - 1, lastGaugeValue.toBuilder()
                    .setCaptureTime(maxCaptureTime)
                    .build());
            map.put(key, gaugeValues);
        }
    }

    private int getLargestRollupLevel() {
        return configRepository.getRollupConfigs().size();
    }

    static List<GaugeValue> rollUpGaugeValues(List<GaugeValue> orderedNonRolledUpGaugeValues,
            String gaugeName, Function<Long, Long> rollupCaptureTimeFn) {
        List<GaugeValue> rolledUpGaugeValues = Lists.newArrayList();
        double currTotal = 0;
        long currWeight = 0;
        long currRollupCaptureTime = Long.MIN_VALUE;
        for (GaugeValue nonRolledUpGaugeValue : orderedNonRolledUpGaugeValues) {
            long captureTime = nonRolledUpGaugeValue.getCaptureTime();
            long rollupCaptureTime = rollupCaptureTimeFn.apply(captureTime);
            if (rollupCaptureTime != currRollupCaptureTime && currWeight > 0) {
                rolledUpGaugeValues.add(GaugeValue.newBuilder()
                        .setGaugeName(gaugeName)
                        .setCaptureTime(currRollupCaptureTime)
                        .setValue(currTotal / currWeight)
                        .setWeight(currWeight)
                        .build());
                currTotal = 0;
                currWeight = 0;
            }
            currRollupCaptureTime = rollupCaptureTime;
            currTotal += nonRolledUpGaugeValue.getValue() * nonRolledUpGaugeValue.getWeight();
            currWeight += nonRolledUpGaugeValue.getWeight();
        }
        if (currWeight > 0) {
            // roll up final one
            long lastCaptureTime =
                    Iterables.getLast(orderedNonRolledUpGaugeValues).getCaptureTime();
            rolledUpGaugeValues.add(GaugeValue.newBuilder()
                    .setGaugeName(gaugeName)
                    .setCaptureTime(lastCaptureTime)
                    .setValue(currTotal / currWeight)
                    .setWeight(currWeight)
                    .build());
        }
        return rolledUpGaugeValues;
    }

    private static boolean isEmpty(Map<String, List<GaugeValue>> map) {
        for (List<GaugeValue> values : map.values()) {
            if (!values.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static DataSeries convertToDataSeriesWithGaps(String dataSeriesName,
            List<GaugeValue> gaugeValues, double gapMillis) {
        DataSeries dataSeries = new DataSeries(dataSeriesName);
        GaugeValue lastGaugeValue = null;
        for (GaugeValue gaugeValue : gaugeValues) {
            if (lastGaugeValue != null
                    && gaugeValue.getCaptureTime() - lastGaugeValue.getCaptureTime() > gapMillis) {
                dataSeries.addNull();
            }
            dataSeries.add(gaugeValue.getCaptureTime(), gaugeValue.getValue());
            lastGaugeValue = gaugeValue;
        }
        return dataSeries;
    }

    private static List<Gauge> addCounterSuffixesIfAndWhereNeeded(List<Gauge> gauges) {
        Set<String> nonCounterGaugeNames = Sets.newHashSet();
        for (Gauge gauge : gauges) {
            if (!gauge.counter()) {
                nonCounterGaugeNames.add(gauge.name());
            }
        }
        List<Gauge> updatedGauges = Lists.newArrayList();
        for (Gauge gauge : gauges) {
            if (gauge.counter() && nonCounterGaugeNames.contains(
                    gauge.name().substring(0, gauge.name().length() - "[counter]".length()))) {
                List<String> displayParts = Lists.newArrayList(gauge.displayParts());
                displayParts.set(displayParts.size() - 1,
                        displayParts.get(displayParts.size() - 1) + " (Counter)");
                updatedGauges.add(ImmutableGauge.builder()
                        .copyFrom(gauge)
                        .display(gauge.display() + " (Counter)")
                        .displayParts(displayParts)
                        .build());
            } else {
                updatedGauges.add(gauge);
            }
        }
        return updatedGauges;
    }

    @Value.Immutable
    interface GaugeValueRequest {
        long from();
        long to();
        // singular because this is used in query string
        ImmutableList<String> gaugeName();
    }

    @Value.Immutable
    interface AllGaugeResponse {
        List<Gauge> allGauges();
        List<String> defaultGaugeNames();
    }

    static class GaugeOrdering extends Ordering<Gauge> {
        @Override
        public int compare(Gauge left, Gauge right) {
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
}
