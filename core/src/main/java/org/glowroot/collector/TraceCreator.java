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

import org.glowroot.markers.Static;
import org.glowroot.transaction.model.Transaction;
import org.glowroot.transaction.model.TransactionMetricImpl;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class TraceCreator {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private TraceCreator() {}

    public static Trace createActiveTrace(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        return createTrace(transaction, true, transaction.isPartial(), captureTime, captureTick);
    }

    public static Trace createPendingTrace(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        return createTrace(transaction, false, transaction.isPartial(), captureTime, captureTick);
    }

    static Trace createCompletedTrace(Transaction transaction, long captureTime)
            throws IOException {
        Long endTick = transaction.getEndTick();
        // endTick is non-null since the trace is complete
        checkNotNull(endTick);
        return createTrace(transaction, false, false, captureTime, endTick);
    }

    // timings for traces that are still active are normalized to the capture tick in order to
    // *attempt* to present a picture of the trace at that exact tick
    // (without using synchronization to block updates to the trace while it is being read)
    private static Trace createTrace(Transaction transaction, boolean active, boolean partial,
            long captureTime, long captureTick) throws IOException {
        Trace.Builder builder = Trace.builder();
        builder.id(transaction.getId());
        builder.active(active);
        builder.partial(partial);
        builder.startTime(transaction.getStartTime());
        builder.captureTime(captureTime);
        builder.duration(captureTick - transaction.getStartTick());
        builder.transactionType(transaction.getTransactionType());
        builder.transactionName(transaction.getTransactionName());
        builder.headline(transaction.getHeadline());
        builder.error(transaction.getError());
        builder.user(transaction.getUser());
        builder.customAttributes(writeCustomAttributesAsString(transaction.getCustomAttributes()));
        builder.customAttributesForIndexing(transaction.getCustomAttributes());
        builder.metrics(writeMetricsAsString(transaction.getRootMetric()));
        builder.threadInfo(transaction.getThreadInfoJson());
        builder.gcInfos(transaction.getGcInfosJson());
        builder.entriesExistence(Existence.YES);
        if (transaction.getOutlierProfile() == null) {
            builder.outlierProfileExistence(Existence.NO);
        } else {
            builder.outlierProfileExistence(Existence.YES);
        }
        if (transaction.getProfile() == null) {
            builder.profileExistence(Existence.NO);
        } else {
            builder.profileExistence(Existence.YES);
        }
        return builder.build();
    }

    @Nullable
    private static String writeCustomAttributesAsString(
            ImmutableSetMultimap<String, String> attributes) throws IOException {
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
    private static String writeMetricsAsString(TransactionMetricImpl rootMetric)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        rootMetric.writeValue(jg);
        jg.close();
        return sb.toString();
    }
}
