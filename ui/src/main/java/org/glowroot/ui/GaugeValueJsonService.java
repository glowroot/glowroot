/*
 * Copyright 2013-2016 the original author or authors.
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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.GaugeValueRepository.Gauge;
import org.glowroot.storage.repo.Utils;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

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

    @GET("/backend/jvm/gauge-values")
    String getGaugeValues(String queryString) throws Exception {
        GaugeValueRequest request = QueryStrings.decode(queryString, GaugeValueRequest.class);
        int rollupLevel =
                rollupLevelService.getGaugeRollupLevelForView(request.from(), request.to());
        long intervalMillis;
        if (rollupLevel == 0) {
            intervalMillis = configRepository.getGaugeCollectionIntervalMillis();
        } else {
            intervalMillis =
                    configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
        }
        double gapMillis = intervalMillis * 1.5;
        long revisedFrom = request.from() - intervalMillis;
        long revisedTo = request.to() + intervalMillis;

        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (String gaugeName : request.gaugeNames()) {
            List<GaugeValue> gaugeValues = getGaugeValues(request.agentRollup(), revisedFrom,
                    revisedTo, gaugeName, rollupLevel);
            dataSeriesList.add(convertToDataSeriesWithGaps(gaugeName, gaugeValues, gapMillis));
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/jvm/all-gauges")
    String getAllGaugeNames(String queryString) throws Exception {
        String agentRollup =
                QueryStrings.decode(queryString, AllGaugeNamesRequest.class).agentRollup();
        List<Gauge> gauges = gaugeValueRepository.getGauges(agentRollup);
        ImmutableList<Gauge> sortedGauges = new GaugeOrdering().immutableSortedCopy(gauges);
        return mapper.writeValueAsString(sortedGauges);
    }

    private List<GaugeValue> getGaugeValues(String agentRollup, long from, long to,
            String gaugeName, int rollupLevel) throws Exception {
        List<GaugeValue> gaugeValues = gaugeValueRepository.readGaugeValues(agentRollup, gaugeName,
                from, to, rollupLevel);
        if (rollupLevel == 0) {
            return gaugeValues;
        }
        long nonRolledUpFrom = from;
        if (!gaugeValues.isEmpty()) {
            long lastRolledUpTime = gaugeValues.get(gaugeValues.size() - 1).getCaptureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<GaugeValue> orderedNonRolledUpGaugeValues = Lists.newArrayList();
        orderedNonRolledUpGaugeValues.addAll(gaugeValueRepository.readGaugeValues(agentRollup,
                gaugeName, nonRolledUpFrom, to, 0));
        gaugeValues = Lists.newArrayList(gaugeValues);
        gaugeValues
                .addAll(rollUpGaugeValues(orderedNonRolledUpGaugeValues, gaugeName, rollupLevel));
        return gaugeValues;
    }

    private List<GaugeValue> rollUpGaugeValues(List<GaugeValue> orderedNonRolledUpGaugeValues,
            String gaugeName, int rollupLevel) {
        long fixedIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
        List<GaugeValue> rolledUpGaugeValues = Lists.newArrayList();
        double currTotal = 0;
        long currWeight = 0;
        long currRollupTime = Long.MIN_VALUE;
        long maxCaptureTime;
        if (orderedNonRolledUpGaugeValues.isEmpty()) {
            maxCaptureTime = Long.MIN_VALUE;
        } else {
            maxCaptureTime = orderedNonRolledUpGaugeValues
                    .get(orderedNonRolledUpGaugeValues.size() - 1).getCaptureTime();
        }
        maxCaptureTime = (long) (Math.floor(maxCaptureTime / 60000) * 60000);
        for (GaugeValue nonRolledUpGaugeValue : orderedNonRolledUpGaugeValues) {
            long captureTime = nonRolledUpGaugeValue.getCaptureTime();
            if (captureTime > maxCaptureTime) {
                break;
            }
            long rollupTime = Utils.getNextRollupTime(captureTime, fixedIntervalMillis);
            if (rollupTime != currRollupTime && currWeight > 0) {
                rolledUpGaugeValues.add(GaugeValue.newBuilder()
                        .setGaugeName(gaugeName)
                        .setCaptureTime(currRollupTime)
                        .setValue(currTotal / currWeight)
                        .setWeight(currWeight)
                        .build());
                currTotal = 0;
                currWeight = 0;
            }
            currRollupTime = rollupTime;
            currTotal += nonRolledUpGaugeValue.getValue() * nonRolledUpGaugeValue.getWeight();
            currWeight += nonRolledUpGaugeValue.getWeight();
        }
        if (currWeight > 0) {
            // roll up final one
            rolledUpGaugeValues.add(GaugeValue.newBuilder()
                    .setGaugeName(gaugeName)
                    .setCaptureTime(maxCaptureTime)
                    .setValue(currTotal / currWeight)
                    .setWeight(currWeight)
                    .build());
        }
        return rolledUpGaugeValues;
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

    @Value.Immutable
    interface AllGaugeNamesRequest {
        String agentRollup();
    }

    @Value.Immutable
    interface GaugeValueRequest {
        String agentRollup();
        long from();
        long to();
        ImmutableList<String> gaugeNames();
    }

    private static class GaugeOrdering extends Ordering<Gauge> {
        @Override
        public int compare(Gauge left, Gauge right) {
            return left.display().compareToIgnoreCase(right.display());
        }
    }
}
