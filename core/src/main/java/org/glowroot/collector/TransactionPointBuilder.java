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

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;

import org.glowroot.trace.model.Profile;
import org.glowroot.trace.model.TraceMetric;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// must be used under an appropriate lock
@NotThreadSafe
class TransactionPointBuilder {

    private static final JsonFactory jsonFactory = new JsonFactory();

    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private long totalMicros;
    private long count;
    private long errorCount;
    private long storedTraceCount;
    private final SimpleTraceMetric syntheticRootTransactionMetric = new SimpleTraceMetric("");
    private final TransactionProfileBuilder transactionProfile = new TransactionProfileBuilder();

    TransactionPointBuilder() {}

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

    void addToTraceMetrics(TraceMetric rootTraceMetric) {
        addToTraceMetrics(rootTraceMetric, syntheticRootTransactionMetric);
    }

    void addToProfile(Profile profile) {
        transactionProfile.addProfile(profile);
    }

    TransactionPoint build(long captureTime) throws IOException {
        return new TransactionPoint(captureTime, totalMicros, count, errorCount, storedTraceCount,
                getTraceMetricsJson(), getProfileJson());
    }

    private void addToTraceMetrics(TraceMetric traceMetric,
            SimpleTraceMetric parentTransactionMetric) {
        String name = traceMetric.getName();
        SimpleTraceMetric transactionMetric = parentTransactionMetric.nestedTraceMetrics.get(name);
        if (transactionMetric == null) {
            transactionMetric = new SimpleTraceMetric(name);
            parentTransactionMetric.nestedTraceMetrics.put(name, transactionMetric);
        }
        transactionMetric.totalMicros += NANOSECONDS.toMicros(traceMetric.getTotal());
        transactionMetric.count += traceMetric.getCount();
        for (TraceMetric nestedMetric : traceMetric.getNestedTraceMetrics()) {
            addToTraceMetrics(nestedMetric, transactionMetric);
        }
    }

    private String getTraceMetricsJson() throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        writeTraceMetric(jg, syntheticRootTransactionMetric);
        jg.close();
        return sb.toString();
    }

    @Nullable
    private String getProfileJson() throws IOException {
        synchronized (transactionProfile.getLock()) {
            return ProfileCharSourceCreator
                    .createProfileJson(transactionProfile.getSyntheticRootNode());
        }
    }

    private void writeTraceMetric(JsonGenerator jg, SimpleTraceMetric metric)
            throws IOException {
        jg.writeStartObject();
        jg.writeStringField("name", metric.name);
        jg.writeNumberField("totalMicros", metric.totalMicros);
        jg.writeNumberField("count", metric.count);
        if (!metric.nestedTraceMetrics.isEmpty()) {
            jg.writeArrayFieldStart("nestedTraceMetrics");
            for (SimpleTraceMetric nestedMetric : metric.nestedTraceMetrics.values()) {
                writeTraceMetric(jg, nestedMetric);
            }
            jg.writeEndArray();
        }
        jg.writeEndObject();
    }

    private static class SimpleTraceMetric {
        private final String name;
        // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
        private long totalMicros;
        private long count;
        private final Map<String, SimpleTraceMetric> nestedTraceMetrics = Maps.newHashMap();
        private SimpleTraceMetric(String name) {
            this.name = name;
        }
    }
}
