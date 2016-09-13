/*
 * Copyright 2012-2016 the original author or authors.
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
package org.glowroot.agent.impl;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import org.glowroot.agent.model.DetailMapWriter;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile.ProfileNode;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

@Styles.Private
public class TraceCreator {

    private TraceCreator() {}

    public static Trace createPartialTrace(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        return createFullTrace(transaction, true, true, captureTime, captureTick);
    }

    public static Trace createCompletedTrace(Transaction transaction, boolean slow)
            throws IOException {
        return createFullTrace(transaction, slow, false, transaction.getCaptureTime(),
                transaction.getEndTick());
    }

    public static Trace.Header createPartialTraceHeader(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        int entryCount = transaction.getEntriesProtobuf(captureTick).size();
        long mainThreadProfileSampleCount = transaction.getMainThreadProfileSampleCount();
        long auxThreadProfileSampleCount = transaction.getAuxThreadProfileSampleCount();
        // only slow transactions reach this point, so setting slow=true (second arg below)
        return createTraceHeader(transaction, true, true, captureTime, captureTick, entryCount,
                mainThreadProfileSampleCount, auxThreadProfileSampleCount);
    }

    public static Trace.Header createCompletedTraceHeader(Transaction transaction)
            throws IOException {
        int entryCount = transaction.getEntriesProtobuf(transaction.getEndTick()).size();
        long mainProfileSampleCount = transaction.getMainThreadProfileSampleCount();
        long auxProfileSampleCount = transaction.getAuxThreadProfileSampleCount();
        // only slow transactions reach this point, so setting slow=true (second arg below)
        return createTraceHeader(transaction, true, false, transaction.getCaptureTime(),
                transaction.getEndTick(), entryCount, mainProfileSampleCount,
                auxProfileSampleCount);
    }

    // timings for traces that are still active are normalized to the capture tick in order to
    // *attempt* to present a picture of the trace at that exact tick
    // (without using synchronization to block updates to the trace while it is being read)
    private static Trace createFullTrace(Transaction transaction, boolean slow, boolean partial,
            long captureTime, long captureTick) throws IOException {
        List<Trace.Entry> entries = transaction.getEntriesProtobuf(captureTick);
        int entryCount = entries.size();
        Profile mainThreadProfile = transaction.getMainThreadProfileProtobuf();
        long mainThreadProfileSampleCount = getProfileSampleCount(mainThreadProfile);
        Profile auxThreadProfile = transaction.getAuxThreadProfileProtobuf();
        long auxThreadProfileSampleCount = getProfileSampleCount(auxThreadProfile);
        Trace.Header header = createTraceHeader(transaction, slow, partial, captureTime,
                captureTick, entryCount, mainThreadProfileSampleCount, auxThreadProfileSampleCount);
        Trace.Builder builder = Trace.newBuilder()
                .setId(transaction.getTraceId())
                .setHeader(header)
                .addAllEntry(entries);
        if (mainThreadProfile != null) {
            builder.setMainThreadProfile(mainThreadProfile);
        }
        if (auxThreadProfile != null) {
            builder.setAuxThreadProfile(auxThreadProfile);
        }
        return builder.setUpdate(transaction.isPartiallyStored())
                .build();
    }

    private static Trace.Header createTraceHeader(Transaction transaction, boolean slow,
            boolean partial, long captureTime, long captureTick, int entryCount,
            long mainProfileSampleCount, long auxProfileSampleCount) throws IOException {
        Trace.Header.Builder builder = Trace.Header.newBuilder();
        builder.setPartial(partial);
        builder.setSlow(slow);
        builder.setAsync(transaction.isAsync());
        ErrorMessage errorMessage = transaction.getErrorMessage();
        builder.setStartTime(transaction.getStartTime());
        builder.setCaptureTime(captureTime);
        builder.setDurationNanos(captureTick - transaction.getStartTick());
        builder.setTransactionType(transaction.getTransactionType());
        builder.setTransactionName(transaction.getTransactionName());
        builder.setHeadline(transaction.getHeadline());
        builder.setUser(transaction.getUser());
        for (Entry<String, Collection<String>> entry : transaction.getAttributes().asMap()
                .entrySet()) {
            builder.addAttributeBuilder()
                    .setName(entry.getKey())
                    .addAllValue(entry.getValue());
        }
        builder.addAllDetailEntry(DetailMapWriter.toProto(transaction.getDetail()));
        if (errorMessage != null) {
            Trace.Error.Builder errorBuilder = builder.getErrorBuilder();
            errorBuilder.setMessage(errorMessage.message());
            Proto.Throwable throwable = errorMessage.throwable();
            if (throwable != null) {
                errorBuilder.setException(throwable);
            }
            errorBuilder.build();
        }
        TimerImpl mainThreadRootTimer = transaction.getMainThreadRootTimer();
        builder.setMainThreadRootTimer(mainThreadRootTimer.toProto());
        RootTimerCollectorImpl auxThreadRootTimers = new RootTimerCollectorImpl();
        transaction.mergeAuxThreadTimersInto(auxThreadRootTimers);
        builder.addAllAuxThreadRootTimer(auxThreadRootTimers.toProto());
        RootTimerCollectorImpl asyncTimers = new RootTimerCollectorImpl();
        transaction.mergeAsyncTimersInto(asyncTimers);
        builder.addAllAsyncTimer(asyncTimers.toProto());
        ThreadStatsCollectorImpl mainThreadStats = new ThreadStatsCollectorImpl();
        mainThreadStats.mergeThreadStats(transaction.getMainThreadStats());
        if (!mainThreadStats.isNA()) {
            builder.setMainThreadStats(mainThreadStats.toProto());
        }
        ThreadStatsCollectorImpl auxThreadStats = new ThreadStatsCollectorImpl();
        transaction.mergeAuxThreadStatsInto(auxThreadStats);
        if (!auxThreadStats.isNA()) {
            builder.setAuxThreadStats(auxThreadStats.toProto());
        }
        builder.setEntryCount(entryCount);
        builder.setEntryLimitExceeded(transaction.isEntryLimitExceeded());
        builder.setMainThreadProfileSampleCount(mainProfileSampleCount);
        builder.setMainThreadProfileSampleLimitExceeded(
                transaction.isMainThreadProfileSampleLimitExceeded());
        builder.setAuxThreadProfileSampleCount(auxProfileSampleCount);
        builder.setAuxThreadProfileSampleLimitExceeded(
                transaction.isAuxThreadProfileSampleLimitExceeded());
        return builder.build();
    }

    private static long getProfileSampleCount(@Nullable Profile profile) {
        if (profile == null) {
            return 0;
        }
        long profileSampleCount = 0;
        for (ProfileNode node : profile.getNodeList()) {
            if (node.getDepth() == 0) {
                profileSampleCount += node.getSampleCount();
            }
        }
        return profileSampleCount;
    }
}
