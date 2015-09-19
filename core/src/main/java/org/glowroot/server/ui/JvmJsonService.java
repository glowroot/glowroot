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
package org.glowroot.server.ui;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.spi.model.GaugeValueOuterClass.GaugeValue;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.live.LiveJvmService;
import org.glowroot.live.LiveJvmService.MBeanTreeRequest;
import org.glowroot.live.LiveThreadDumpService;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.GaugeValueRepository;
import org.glowroot.server.repo.GaugeValueRepository.Gauge;

@JsonService
class JvmJsonService {

    private static final Logger logger = LoggerFactory.getLogger(JvmJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final GaugeValueRepository gaugeValueRepository;
    private final ConfigRepository configRepository;
    private final LiveJvmService liveJvmService;
    private final LiveThreadDumpService liveThreadDumpService;

    private final Clock clock;

    JvmJsonService(GaugeValueRepository gaugeValueDao, ConfigRepository configRepository,
            LiveJvmService liveJvmService, LiveThreadDumpService liveThreadDumpService,
            Clock clock) {
        this.gaugeValueRepository = gaugeValueDao;
        this.configRepository = configRepository;
        this.liveJvmService = liveJvmService;
        this.liveThreadDumpService = liveThreadDumpService;
        this.clock = clock;
    }

    @GET("/backend/jvm/gauge-values")
    String getGaugeValues(String queryString) throws Exception {
        GaugeValueRequest request = QueryStrings.decode(queryString, GaugeValueRequest.class);
        int rollupLevel = gaugeValueRepository.getRollupLevelForView(request.from(), request.to());
        long intervalMillis;
        if (rollupLevel == 0) {
            intervalMillis = configRepository.getGaugeCollectionIntervalMillis();
        } else {
            intervalMillis =
                    configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
        }
        double gapMillis = intervalMillis * 1.5;
        // 2x in order to deal with displaying deltas
        long revisedFrom = request.from() - 2 * intervalMillis;
        long revisedTo = request.to() + intervalMillis;

        long liveCaptureTime = clock.currentTimeMillis();
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (String gaugeName : request.gaugeNames()) {
            List<GaugeValue> gaugeValues =
                    getGaugeValues(revisedFrom, revisedTo, gaugeName, rollupLevel, liveCaptureTime);
            if (!gaugeValues.isEmpty()) {
                dataSeriesList.add(convertToDataSeriesWithGaps(gaugeName, gaugeValues, gapMillis));
            }
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
    String getAllGaugeNames() throws Exception {
        List<Gauge> gauges = gaugeValueRepository.getGauges();
        ImmutableList<Gauge> sortedGauges = new GaugeOrdering().immutableSortedCopy(gauges);
        return mapper.writeValueAsString(sortedGauges);
    }

    @GET("/backend/jvm/mbean-tree")
    String getMBeanTree(String queryString) throws Exception {
        MBeanTreeRequest request = QueryStrings.decode(queryString, MBeanTreeRequest.class);
        return mapper.writeValueAsString(liveJvmService.getMBeanTree(request));
    }

    @GET("/backend/jvm/mbean-attribute-map")
    String getMBeanAttributeMap(String queryString) throws Exception {
        MBeanAttributeMapRequest request =
                QueryStrings.decode(queryString, MBeanAttributeMapRequest.class);
        return mapper.writeValueAsString(
                liveJvmService.getMBeanSortedAttributeMap(request.objectName()));
    }

    @POST("/backend/jvm/perform-gc")
    void performGC() throws IOException {
        // using MemoryMXBean.gc() instead of System.gc() in hope that it will someday bypass
        // -XX:+DisableExplicitGC (see https://bugs.openjdk.java.net/browse/JDK-6396411)
        ManagementFactory.getMemoryMXBean().gc();
    }

    @GET("/backend/jvm/thread-dump")
    String getThreadDump() throws IOException {
        return mapper.writeValueAsString(liveThreadDumpService.getAllThreads());
    }

    @GET("/backend/jvm/heap-dump-default-dir")
    String getHeapDumpDefaultDir() throws Exception {
        return mapper.writeValueAsString(liveJvmService.getHeapDumpDefaultDirectory());
    }

    @POST("/backend/jvm/available-disk-space")
    String getAvailableDiskSpace(String content) throws IOException {
        RequestWithDirectory request =
                mapper.readValue(content, ImmutableRequestWithDirectory.class);
        try {
            return Long.toString(liveJvmService.getAvailableDiskSpace(request.directory()));
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            // this is for specific common errors, e.g. "Directory doesn't exist"
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("error", e.getMessage());
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
    }

    @POST("/backend/jvm/dump-heap")
    String dumpHeap(String content) throws Exception {
        RequestWithDirectory request =
                mapper.readValue(content, ImmutableRequestWithDirectory.class);
        try {
            return mapper.writeValueAsString(liveJvmService.dumpHeap(request.directory()));
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            // this is for specific common errors, e.g. "Directory doesn't exist"
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("error", e.getMessage());
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
    }

    @GET("/backend/jvm/process-info")
    String getProcessInfo() throws Exception {
        return mapper.writeValueAsString(liveJvmService.getProcessInfo());
    }

    @GET("/backend/jvm/system-properties")
    String getSystemProperties() throws IOException {
        // can't use Maps.newTreeMap() because of OpenJDK6 type inference bug
        // see https://code.google.com/p/guava-libraries/issues/detail?id=635
        Map<String, String> sortedProperties =
                new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        sortedProperties.putAll(liveJvmService.getSystemProperties());

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartArray();
        for (Entry<String, String> entry : sortedProperties.entrySet()) {
            jg.writeStartObject();
            jg.writeStringField("name", entry.getKey());
            jg.writeStringField("value", entry.getValue());
            jg.writeEndObject();
        }
        jg.writeEndArray();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/jvm/capabilities")
    String getCapabilities() throws IOException {
        return mapper.writeValueAsString(liveJvmService.getCapabilities());
    }

    private List<GaugeValue> getGaugeValues(long from, long to, String gaugeName, int rollupLevel,
            long liveCaptureTime) throws Exception {
        ImmutableList<GaugeValue> gaugeValues =
                gaugeValueRepository.readGaugeValues(gaugeName, from, to, rollupLevel);
        if (rollupLevel == 0) {
            return gaugeValues;
        }
        long nonRolledUpFrom = from;
        if (!gaugeValues.isEmpty()) {
            long lastRolledUpTime = gaugeValues.get(gaugeValues.size() - 1).getCaptureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<GaugeValue> allGaugeValues = Lists.newArrayList(gaugeValues);
        allGaugeValues.addAll(gaugeValueRepository.readManuallyRolledUpGaugeValues(nonRolledUpFrom,
                to, gaugeName, rollupLevel, liveCaptureTime));
        return allGaugeValues;
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
    interface GaugeValueRequest {
        long from();
        long to();
        ImmutableList<String> gaugeNames();
    }

    @Value.Immutable
    abstract static class MBeanAttributeMapRequest {
        abstract String objectName();
    }

    @Value.Immutable
    abstract static class RequestWithDirectory {
        abstract String directory();
    }

    @Value.Immutable
    abstract static class ThreadDumpItem {
        abstract String objectName();
    }

    private static class GaugeOrdering extends Ordering<Gauge> {
        @Override
        public int compare(Gauge left, Gauge right) {
            return left.display().compareToIgnoreCase(right.display());
        }
    }
}
