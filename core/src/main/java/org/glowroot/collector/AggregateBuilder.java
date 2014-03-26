/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.collector;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;

import org.glowroot.markers.NotThreadSafe;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.trace.model.Metric;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// must be used under an appropriate lock
@NotThreadSafe
public class AggregateBuilder {

    private static final JsonFactory jsonFactory = new JsonFactory();

    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private long totalMicros;
    private long count;
    private long errorCount;
    private long storedTraceCount;
    private final AggregateMetric syntheticRootAggregateMetric = new AggregateMetric("");

    AggregateBuilder() {}

    @OnlyUsedByTests
    public AggregateBuilder(long totalMicros, long count) {
        this.totalMicros = totalMicros;
        this.count = count;
    }

    public long getTotalMicros() {
        return totalMicros;
    }

    public long getCount() {
        return count;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getStoredTraceCount() {
        return storedTraceCount;
    }

    public String getMetricsJson() throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        writeAggregateMetric(jg, syntheticRootAggregateMetric);
        jg.close();
        return sb.toString();
    }

    void add(long duration) {
        totalMicros += NANOSECONDS.toMicros(duration);
        count++;
    }

    void addToErrorCount() {
        errorCount++;
    }

    void addToStoredTraceCount() {
        storedTraceCount++;
    }

    void addToMetrics(Metric rootMetric) {
        addToMetrics(rootMetric, syntheticRootAggregateMetric);
    }

    private void addToMetrics(Metric metric, AggregateMetric parentAggregateMetric) {
        String name = metric.getMetricName().getName();
        AggregateMetric aggregateMetric = parentAggregateMetric.nestedMetrics.get(name);
        if (aggregateMetric == null) {
            aggregateMetric = new AggregateMetric(name);
            parentAggregateMetric.nestedMetrics.put(name, aggregateMetric);
        }
        aggregateMetric.totalMicros += NANOSECONDS.toMicros(metric.getTotal());
        aggregateMetric.count += metric.getCount();
        for (Metric nestedMetric : metric.getNestedMetrics()) {
            addToMetrics(nestedMetric, aggregateMetric);
        }
    }

    private void writeAggregateMetric(JsonGenerator jg, AggregateMetric metric) throws IOException {
        jg.writeStartObject();
        jg.writeStringField("name", metric.name);
        jg.writeNumberField("totalMicros", metric.totalMicros);
        jg.writeNumberField("count", metric.count);
        if (!metric.nestedMetrics.isEmpty()) {
            jg.writeArrayFieldStart("nestedMetrics");
            for (AggregateMetric nestedMetric : metric.nestedMetrics.values()) {
                writeAggregateMetric(jg, nestedMetric);
            }
            jg.writeEndArray();
        }
        jg.writeEndObject();
    }

    static class AggregateMetric {
        private final String name;
        // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
        private long totalMicros;
        private long count;
        private final Map<String, AggregateMetric> nestedMetrics = Maps.newHashMap();
        private AggregateMetric(String name) {
            this.name = name;
        }
    }
}
