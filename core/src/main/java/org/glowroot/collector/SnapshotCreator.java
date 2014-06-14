/*
 * Copyright 2012-2014 the original author or authors.
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
import java.util.Collection;
import java.util.Map.Entry;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.collector.Snapshot.Existence;
import org.glowroot.markers.Static;
import org.glowroot.trace.model.Trace;
import org.glowroot.trace.model.TraceMetric;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class SnapshotCreator {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private SnapshotCreator() {}

    public static Snapshot createActiveSnapshot(Trace trace, long captureTime, long captureTick)
            throws IOException {
        return createSnapshot(trace, true, trace.isStuck(), captureTime, captureTick);
    }

    public static Snapshot createPendingSnapshot(Trace trace, long captureTime, long captureTick)
            throws IOException {
        return createSnapshot(trace, false, trace.isStuck(), captureTime, captureTick);
    }

    static Snapshot createCompletedSnapshot(Trace trace, long captureTime) throws IOException {
        Long endTick = trace.getEndTick();
        // endTick is non-null since the trace is complete
        checkNotNull(endTick);
        return createSnapshot(trace, false, false, captureTime, endTick);
    }

    // timings for traces that are still active are normalized to the capture tick in order to
    // *attempt* to present a picture of the trace at that exact tick
    // (without using synchronization to block updates to the trace while it is being read)
    private static Snapshot createSnapshot(Trace trace, boolean active, boolean stuck,
            long captureTime, long captureTick) throws IOException {
        Snapshot.Builder builder = Snapshot.builder();
        builder.id(trace.getId());
        builder.active(active);
        builder.stuck(stuck);
        builder.startTime(trace.getStartTime());
        builder.captureTime(captureTime);
        builder.duration(captureTick - trace.getStartTick());
        builder.background(trace.isBackground());
        builder.transactionName(trace.getTransactionName());
        builder.headline(trace.getHeadline());
        builder.error(trace.getError());
        builder.user(trace.getUser());
        builder.attributes(writeAttributesAsString(trace.getAttributes()));
        builder.attributesForIndexing(trace.getAttributes());
        builder.traceMetrics(writeMetricsAsString(trace.getRootMetric()));
        builder.jvmInfo(trace.getJvmInfoJson());
        builder.spansExistence(Existence.YES);
        if (trace.getCoarseProfile() == null) {
            builder.coarseProfileExistence(Existence.NO);
        } else {
            builder.coarseProfileExistence(Existence.YES);
        }
        if (trace.getFineProfile() == null) {
            builder.fineProfileExistence(Existence.NO);
        } else {
            builder.fineProfileExistence(Existence.YES);
        }
        return builder.build();
    }

    @Nullable
    private static String writeAttributesAsString(ImmutableSetMultimap<String, String> attributes)
            throws IOException {
        if (attributes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        for (Entry<String, Collection<String>> entry : attributes.asMap().entrySet()) {
            jg.writeArrayFieldStart(entry.getKey());
            for (String value : entry.getValue()) {
                jg.writeString(value);
            }
            jg.writeEndArray();
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @Nullable
    private static String writeMetricsAsString(TraceMetric rootMetric) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        rootMetric.writeValue(jg);
        jg.close();
        return sb.toString();
    }
}
