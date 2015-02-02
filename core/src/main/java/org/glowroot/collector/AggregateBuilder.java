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

import org.glowroot.common.ScratchBuffer;
import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.ThreadInfoComponent.ThreadInfoData;
import org.glowroot.transaction.model.Transaction;
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
    private @Nullable Long totalCpuMicros;
    private @Nullable Long totalBlockedMicros;
    private @Nullable Long totalWaitedMicros;
    private @Nullable Long totalAllocatedBytes;
    private long profileSampleCount;
    private long traceCount;
    // histogram uses microseconds to reduce (or at least simplify) bucket allocations
    private final LazyHistogram histogram = new LazyHistogram();
    private final AggregateMetric syntheticRootMetric = new AggregateMetric("");
    private final AggregateProfileBuilder aggregateProfile = new AggregateProfileBuilder();

    AggregateBuilder(String transactionType, @Nullable String transactionName) {
        this.transactionType = transactionType;
        this.transactionName = transactionName;
    }

    void add(Transaction transaction) {
        long durationMicros = NANOSECONDS.toMicros(transaction.getDuration());
        totalMicros += durationMicros;
        if (transaction.getError() != null) {
            errorCount++;
        }
        if (transaction.willBeStored()) {
            traceCount++;
        }
        transactionCount++;
        ThreadInfoData threadInfo = transaction.getThreadInfo();
        if (threadInfo != null) {
            totalCpuMicros = nullAwareAdd(totalCpuMicros,
                    nullAwareNanosToMicros(threadInfo.threadCpuTime()));
            totalBlockedMicros = nullAwareAdd(totalBlockedMicros,
                    nullAwareNanosToMicros(threadInfo.threadBlockedTime()));
            totalWaitedMicros = nullAwareAdd(totalWaitedMicros,
                    nullAwareNanosToMicros(threadInfo.threadWaitedTime()));
            totalAllocatedBytes = nullAwareAdd(totalAllocatedBytes,
                    threadInfo.threadAllocatedBytes());
        }
        histogram.add(durationMicros);
    }

    void addToMetrics(TransactionMetricImpl rootTransactionMetric) {
        syntheticRootMetric.totalMicros += NANOSECONDS.toMicros(rootTransactionMetric.getTotal());
        syntheticRootMetric.count += rootTransactionMetric.getCount();
        mergeAsChildMetric(syntheticRootMetric, rootTransactionMetric);
    }

    void addToProfile(Profile profile) {
        aggregateProfile.addProfile(profile);
        profileSampleCount += profile.getSampleCount();
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
                .totalCpuMicros(totalCpuMicros)
                .totalBlockedMicros(totalBlockedMicros)
                .totalWaitedMicros(totalWaitedMicros)
                .totalAllocatedBytes(totalAllocatedBytes)
                .metrics(getMetricsJson())
                .histogram(histogram)
                .profileSampleCount(profileSampleCount)
                .traceCount(traceCount)
                .profile(getProfileJson())
                .build();
    }

    TransactionSummary getLiveTransactionSummary() {
        return ImmutableTransactionSummary.builder()
                .transactionName(transactionName)
                .totalMicros(totalMicros)
                .transactionCount(transactionCount)
                .build();
    }

    ErrorSummary getLiveErrorSummary() {
        return ImmutableErrorSummary.builder()
                .transactionName(transactionName)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .build();
    }

    ErrorPoint buildErrorPoint(long captureTime) {
        return ImmutableErrorPoint.builder()
                .captureTime(captureTime)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .build();
    }

    long getTransactionCount() {
        return transactionCount;
    }

    long getProfileSampleCount() {
        return profileSampleCount;
    }

    private void mergeAsChildMetric(AggregateMetric parentAggregateMetric,
            TransactionMetricImpl transactionMetric) {
        String name = transactionMetric.getName();
        AggregateMetric aggregateMetric = parentAggregateMetric.nestedMetrics.get(name);
        if (aggregateMetric == null) {
            aggregateMetric = new AggregateMetric(name);
            parentAggregateMetric.nestedMetrics.put(name, aggregateMetric);
        }
        aggregateMetric.totalMicros += NANOSECONDS.toMicros(transactionMetric.getTotal());
        aggregateMetric.count += transactionMetric.getCount();
        for (TransactionMetricImpl nestedTransactionMetric : transactionMetric.getNestedMetrics()) {
            mergeAsChildMetric(aggregateMetric, nestedTransactionMetric);
        }
    }

    private String getMetricsJson() throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        writeMetric(jg, syntheticRootMetric);
        jg.close();
        return sb.toString();
    }

    @Nullable
    String getProfileJson() throws IOException {
        return ProfileCharSourceCreator.createProfileJson(aggregateProfile.getSyntheticRootNode());
    }

    private void writeMetric(JsonGenerator jg, AggregateMetric aggregateMetric) throws IOException {
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

    private static @Nullable Long nullAwareNanosToMicros(@Nullable Long nanoseconds) {
        if (nanoseconds == null) {
            return null;
        }
        return NANOSECONDS.toMicros(nanoseconds);
    }

    private static @Nullable Long nullAwareAdd(@Nullable Long x, @Nullable Long y) {
        if (x == null) {
            return y;
        }
        if (y == null) {
            return x;
        }
        return x + y;
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
