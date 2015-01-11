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
package org.glowroot.collector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.TransactionMetricImpl;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

// must be used under an appropriate lock
class AggregateBuilder {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private final String transactionType;
    private final @Nullable String transactionName;
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private long totalMicros;
    private long errorCount;
    private long transactionCount;
    private long profileSampleCount;
    // histogram uses microseconds to reduce (or at least simplify) bucket allocations
    private final LazyHistogram histogram = new LazyHistogram();
    private final AggregateMetric syntheticRootMetric = new AggregateMetric("");
    private final AggregateProfileBuilder aggregateProfile = new AggregateProfileBuilder();

    AggregateBuilder(String transactionType, @Nullable String transactionName) {
        this.transactionType = transactionType;
        this.transactionName = transactionName;
    }

    void add(long duration, boolean error) {
        long durationMicros = NANOSECONDS.toMicros(duration);
        totalMicros += durationMicros;
        if (error) {
            errorCount++;
        }
        transactionCount++;
        histogram.add(durationMicros);
    }

    void addToMetrics(TransactionMetricImpl rootTransactionMetric) {
        addToMetrics(rootTransactionMetric, syntheticRootMetric);
    }

    void addToProfile(Profile profile) {
        aggregateProfile.addProfile(profile);
        profileSampleCount += profile.getSyntheticRootNode().getSampleCount();
    }

    Aggregate build(long captureTime, ScratchBuffer scratchBuffer) throws IOException {
        ByteBuffer buffer = scratchBuffer.getBuffer(histogram.getNeededByteBufferCapacity());
        buffer.clear();
        histogram.encodeIntoByteBuffer(buffer);
        int size = buffer.position();
        buffer.flip();
        byte[] histogram = new byte[size];
        buffer.get(histogram, 0, size);
        return ImmutableAggregate.builder()
                .transactionType(transactionType)
                .transactionName(transactionName)
                .captureTime(captureTime)
                .totalMicros(totalMicros)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .metrics(getMetricsJson())
                .histogram(histogram)
                .profileSampleCount(profileSampleCount)
                .profile(getProfileJson())
                .build();
    }

    private void addToMetrics(TransactionMetricImpl transactionMetric,
            AggregateMetric parentAggregateMetric) {
        String name = transactionMetric.getName();
        AggregateMetric aggregateMetric = parentAggregateMetric.nestedMetrics.get(name);
        if (aggregateMetric == null) {
            aggregateMetric = new AggregateMetric(name);
            parentAggregateMetric.nestedMetrics.put(name, aggregateMetric);
        }
        aggregateMetric.totalMicros += NANOSECONDS.toMicros(transactionMetric.getTotal());
        aggregateMetric.count += transactionMetric.getCount();
        for (TransactionMetricImpl nestedTransactionMetric : transactionMetric.getNestedMetrics()) {
            addToMetrics(nestedTransactionMetric, aggregateMetric);
        }
    }

    private String getMetricsJson() throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        writeMetric(jg, syntheticRootMetric);
        jg.close();
        return sb.toString();
    }

    private @Nullable String getProfileJson() throws IOException {
        synchronized (aggregateProfile.getLock()) {
            return ProfileCharSourceCreator.createProfileJson(
                    aggregateProfile.getSyntheticRootNode());
        }
    }

    private void writeMetric(JsonGenerator jg, AggregateMetric aggregateMetric)
            throws IOException {
        jg.writeStartObject();
        jg.writeStringField("name", aggregateMetric.name);
        jg.writeNumberField("totalMicros", aggregateMetric.totalMicros);
        jg.writeNumberField("count", aggregateMetric.count);
        if (!aggregateMetric.nestedMetrics.isEmpty()) {
            jg.writeArrayFieldStart("nestedMetrics");
            for (AggregateMetric metric : aggregateMetric.nestedMetrics.values()) {
                writeMetric(jg, metric);
            }
            jg.writeEndArray();
        }
        jg.writeEndObject();
    }

    static class ScratchBuffer {

        private @MonotonicNonNull ByteBuffer buffer;

        ByteBuffer getBuffer(int capacity) {
            if (buffer == null || buffer.capacity() < capacity) {
                buffer = ByteBuffer.allocate(capacity);
            }
            return buffer;
        }
    }

    private static class AggregateMetric {
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
