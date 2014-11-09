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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;

import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.TransactionMetricImpl;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

// must be used under an appropriate lock
class AggregateBuilder {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private final String transactionType;
    @Nullable
    private final String transactionName;
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private long totalMicros;
    private long errorCount;
    private long transactionCount;
    private long profileSampleCount;
    private final AggregateMetric syntheticRootMetric = new AggregateMetric("");
    private final AggregateProfileBuilder aggregateProfile = new AggregateProfileBuilder();

    AggregateBuilder(String transactionType, @Nullable String transactionName) {
        this.transactionType = transactionType;
        this.transactionName = transactionName;
    }

    void add(long duration, boolean error) {
        totalMicros += NANOSECONDS.toMicros(duration);
        if (error) {
            errorCount++;
        }
        transactionCount++;
    }

    void addToMetrics(TransactionMetricImpl rootTransactionMetric) {
        addToMetrics(rootTransactionMetric, syntheticRootMetric);
    }

    void addToProfile(Profile profile) {
        aggregateProfile.addProfile(profile);
        profileSampleCount += profile.getSyntheticRootNode().getSampleCount();
    }

    Aggregate build(long captureTime) throws IOException {
        String profile = getProfileJson();
        Existence profileExistence = profile != null ? Existence.YES : Existence.NO;
        return ImmutableAggregate.builder()
                .transactionType(transactionType)
                .transactionName(transactionName)
                .captureTime(captureTime)
                .totalMicros(totalMicros)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .metrics(getMetricsJson())
                .profileExistence(profileExistence)
                .profileSampleCount(profileSampleCount)
                .profile(profile)
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

    @Nullable
    private String getProfileJson() throws IOException {
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
