/*
 * Copyright 2013-2015 the original author or authors.
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

import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.GaugeValueRepository.Gauge;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;

@JsonService
class GaugeValueJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final GaugeValueRepository gaugeValueRepository;
    private final ConfigRepository configRepository;

    private final Clock clock;

    GaugeValueJsonService(GaugeValueRepository gaugeValueRepository,
            ConfigRepository configRepository, Clock clock) {
        this.gaugeValueRepository = gaugeValueRepository;
        this.configRepository = configRepository;
        this.clock = clock;
    }

    @GET("/backend/jvm/gauge-values")
    String getGaugeValues(String queryString) throws Exception {
        GaugeValueRequest request = QueryStrings.decode(queryString, GaugeValueRequest.class);
        int rollupLevel = gaugeValueRepository.getRollupLevelForView(request.serverRollup(),
                request.from(), request.to());
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

        long liveCaptureTime = clock.currentTimeMillis();
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (String gaugeName : request.gaugeNames()) {
            List<GaugeValue> gaugeValues = getGaugeValues(request.serverRollup(), revisedFrom,
                    revisedTo, gaugeName, rollupLevel, liveCaptureTime, gapMillis);
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
        String serverRollup =
                QueryStrings.decode(queryString, AllGaugeNamesRequest.class).serverRollup();
        List<Gauge> gauges = gaugeValueRepository.getGauges(serverRollup);
        ImmutableList<Gauge> sortedGauges = new GaugeOrdering().immutableSortedCopy(gauges);
        return mapper.writeValueAsString(sortedGauges);
    }

    private List<GaugeValue> getGaugeValues(String serverRollup, long from, long to,
            String gaugeName, int rollupLevel, long liveCaptureTime, double gapMillis)
                    throws Exception {
        List<GaugeValue> gaugeValues = gaugeValueRepository.readGaugeValues(serverRollup, gaugeName,
                from, to, rollupLevel);
        if (rollupLevel == 0) {
            return gaugeValues;
        }
        long nonRolledUpFrom = from;
        if (!gaugeValues.isEmpty()) {
            long lastRolledUpTime = gaugeValues.get(gaugeValues.size() - 1).getCaptureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        if (liveCaptureTime - nonRolledUpFrom > gapMillis) {
            // only display "live" rollup if there is gap since the last value
            // this avoids the confusion of an empty chart shortly after starting the jvm
            // (when in default 4 hour rollup level)
            // while at the same time avoiding the confusion of always showing the "live" rollup
            // which can look like a strange spiky value at the end of a chart since it can be based
            // on a very short amount of time (e.g. 5 seconds) and so not smoothed out with enough
            // data yet
            gaugeValues = Lists.newArrayList(gaugeValues);
            gaugeValues.addAll(gaugeValueRepository.readManuallyRolledUpGaugeValues(serverRollup,
                    nonRolledUpFrom, to, gaugeName, rollupLevel, liveCaptureTime));
        }
        return gaugeValues;
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
        String serverRollup();
    }

    @Value.Immutable
    interface GaugeValueRequest {
        String serverRollup();
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
