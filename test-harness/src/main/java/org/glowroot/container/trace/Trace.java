/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container.trace;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSetMultimap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import static org.glowroot.container.common.ObjectMappers.checkNotNullItemsForProperty;
import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Trace {

    private final String id;
    private final boolean active;
    private final boolean stuck;
    private final long startTime;
    private final long captureTime;
    private final long duration;
    private final boolean background;
    private final String transactionName;
    private final String headline;
    @Nullable
    private final String error;
    @Nullable
    private final String user;
    private final ImmutableSetMultimap<String, String> attributes;
    private final TraceMetric rootTraceMetric;
    private final JvmInfo jvmInfo;
    private final Existence spansExistence;
    private final Existence coarseProfileExistence;
    private final Existence fineProfileExistence;

    private Trace(String id, boolean active, boolean stuck, long startTime, long captureTime,
            long duration, boolean background, String transactionName, String headline,
            @Nullable String error, @Nullable String user,
            ImmutableSetMultimap<String, String> attributes, TraceMetric rootTraceMetric,
            JvmInfo jvmInfo, Existence spansExistence, Existence coarseProfileExistence,
            Existence fineProfileExistence) {
        this.id = id;
        this.active = active;
        this.stuck = stuck;
        this.startTime = startTime;
        this.captureTime = captureTime;
        this.duration = duration;
        this.background = background;
        this.transactionName = transactionName;
        this.headline = headline;
        this.error = error;
        this.user = user;
        this.attributes = attributes;
        this.rootTraceMetric = rootTraceMetric;
        this.jvmInfo = jvmInfo;
        this.spansExistence = spansExistence;
        this.coarseProfileExistence = coarseProfileExistence;
        this.fineProfileExistence = fineProfileExistence;
    }

    public String getId() {
        return id;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isStuck() {
        return stuck;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isBackground() {
        return background;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public String getHeadline() {
        return headline;
    }

    @Nullable
    public String getError() {
        return error;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    public ImmutableSetMultimap<String, String> getAttributes() {
        return attributes;
    }

    public TraceMetric getRootTraceMetric() {
        return rootTraceMetric;
    }

    public JvmInfo getJvmInfo() {
        return jvmInfo;
    }

    public Existence getSpansExistence() {
        return spansExistence;
    }

    public Existence getCoarseProfileExistence() {
        return coarseProfileExistence;
    }

    public Existence getFineProfileExistence() {
        return fineProfileExistence;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("active", active)
                .add("stuck", stuck)
                .add("startTime", startTime)
                .add("captureTime", captureTime)
                .add("duration", duration)
                .add("background", background)
                .add("transactionName", transactionName)
                .add("headline", headline)
                .add("error", error)
                .add("user", user)
                .add("attributes", attributes)
                .add("rootTraceMetric", rootTraceMetric)
                .add("jvmInfo", jvmInfo)
                .add("spansExistence", spansExistence)
                .add("coarseProfileExistence", coarseProfileExistence)
                .add("fineProfileExistence", fineProfileExistence)
                .toString();
    }

    @JsonCreator
    static Trace readValue(
            @JsonProperty("id") @Nullable String id,
            @JsonProperty("active") @Nullable Boolean active,
            @JsonProperty("stuck") @Nullable Boolean stuck,
            @JsonProperty("startTime") @Nullable Long startTime,
            @JsonProperty("captureTime") @Nullable Long captureTime,
            @JsonProperty("duration") @Nullable Long duration,
            @JsonProperty("background") @Nullable Boolean background,
            @JsonProperty("transactionName") @Nullable String transactionName,
            @JsonProperty("headline") @Nullable String headline,
            @JsonProperty("error") @Nullable String error,
            @JsonProperty("user") @Nullable String user,
            @JsonProperty("attributes") @Nullable Map<String, /*@Nullable*/List</*@Nullable*/String>> attributes,
            @JsonProperty("traceMetrics") @Nullable TraceMetric rootTraceMetric,
            @JsonProperty("jvmInfo") @Nullable JvmInfo jvmInfo,
            @JsonProperty("spansExistence") @Nullable Existence spansExistence,
            @JsonProperty("coarseProfileExistence") @Nullable Existence coarseProfileExistence,
            @JsonProperty("fineProfileExistence") @Nullable Existence fineProfileExistence)
            throws JsonMappingException {
        checkRequiredProperty(id, "id");
        checkRequiredProperty(active, "active");
        checkRequiredProperty(stuck, "stuck");
        checkRequiredProperty(startTime, "startTime");
        checkRequiredProperty(captureTime, "captureTime");
        checkRequiredProperty(duration, "duration");
        checkRequiredProperty(background, "background");
        checkRequiredProperty(transactionName, "transactionName");
        checkRequiredProperty(headline, "headline");
        checkRequiredProperty(rootTraceMetric, "rootTraceMetric");
        checkRequiredProperty(jvmInfo, "jvmInfo");
        checkRequiredProperty(spansExistence, "spansExistence");
        checkRequiredProperty(coarseProfileExistence, "coarseProfileExistence");
        checkRequiredProperty(fineProfileExistence, "fineProfileExistence");
        ImmutableSetMultimap.Builder<String, String> theAttributes = ImmutableSetMultimap.builder();
        if (attributes != null) {
            for (Entry<String, /*@Nullable*/List</*@Nullable*/String>> entry : attributes
                    .entrySet()) {
                List</*@Nullable*/String> uncheckedValues = entry.getValue();
                if (uncheckedValues == null) {
                    throw new JsonMappingException(
                            "Null value not allowed for attribute map value");
                }
                List<String> values =
                        checkNotNullItemsForProperty(uncheckedValues, "traceAttributes");
                theAttributes.putAll(entry.getKey(), values);
            }
        }
        return new Trace(id, active, stuck, startTime, captureTime, duration, background,
                transactionName, headline, error, user, theAttributes.build(), rootTraceMetric,
                jvmInfo, spansExistence, coarseProfileExistence, fineProfileExistence);
    }

    public enum Existence {
        YES, NO, EXPIRED;
    }
}
