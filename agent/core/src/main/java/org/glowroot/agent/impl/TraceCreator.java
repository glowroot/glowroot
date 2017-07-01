/*
 * Copyright 2012-2017 the original author or authors.
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

import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import org.glowroot.agent.collector.Collector.EntryVisitor;
import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.agent.collector.Collector.TraceVisitor;
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

    public static TraceReader createTraceReaderForPartial(final Transaction transaction,
            final long captureTime, final long captureTick) {
        final boolean partial = true;
        return new TraceReaderImpl(captureTime, transaction.getTraceId(), partial,
                transaction.isPartiallyStored()) {
            @Override
            public void accept(TraceVisitor traceVisitor) throws Exception {
                createFullTrace(transaction, true, partial, captureTime, captureTick, traceVisitor);
            }
        };
    }

    public static TraceReader createTraceReaderForCompleted(final Transaction transaction,
            final boolean slow) {
        final boolean partial = false;
        return new TraceReaderImpl(transaction.getCaptureTime(), transaction.getTraceId(),
                partial, transaction.isPartiallyStored()) {
            @Override
            public void accept(TraceVisitor traceVisitor) throws Exception {
                createFullTrace(transaction, slow, partial, transaction.getCaptureTime(),
                        transaction.getEndTick(), traceVisitor);
            }
        };
    }

    public static Trace.Header createPartialTraceHeader(Transaction transaction, long captureTime,
            long captureTick) throws Exception {
        // TODO optimize, this is excessive to construct entries just to get count
        CountingEntryVisitor entryVisitor = new CountingEntryVisitor();
        transaction.accept(captureTick, entryVisitor);
        long mainThreadProfileSampleCount = transaction.getMainThreadProfileSampleCount();
        long auxThreadProfileSampleCount = transaction.getAuxThreadProfileSampleCount();
        // only slow transactions reach this point, so setting slow=true (second arg below)
        return createTraceHeader(transaction, true, true, captureTime, captureTick,
                entryVisitor.count, mainThreadProfileSampleCount, auxThreadProfileSampleCount);
    }

    public static Trace.Header createCompletedTraceHeader(Transaction transaction)
            throws Exception {
        // TODO optimize, this is excessive to construct entries just to get count
        CountingEntryVisitor entryVisitor = new CountingEntryVisitor();
        transaction.accept(transaction.getEndTick(), entryVisitor);
        long mainProfileSampleCount = transaction.getMainThreadProfileSampleCount();
        long auxProfileSampleCount = transaction.getAuxThreadProfileSampleCount();
        // only slow transactions reach this point, so setting slow=true (second arg below)
        return createTraceHeader(transaction, true, false, transaction.getCaptureTime(),
                transaction.getEndTick(), entryVisitor.count, mainProfileSampleCount,
                auxProfileSampleCount);
    }

    public static List<Trace.SharedQueryText> toProto(List<String> sharedQueryTexts) {
        List<Trace.SharedQueryText> protos = Lists.newArrayList();
        for (String sharedQueryText : sharedQueryTexts) {
            protos.add(Trace.SharedQueryText.newBuilder()
                    .setFullText(sharedQueryText)
                    .build());
        }
        return protos;
    }

    // timings for traces that are still active are normalized to the capture tick in order to
    // *attempt* to present a picture of the trace at that exact tick
    // (without using synchronization to block updates to the trace while it is being read)
    private static void createFullTrace(Transaction transaction, boolean slow, boolean partial,
            long captureTime, long captureTick, TraceVisitor traceVisitor) throws Exception {
        CountingEntryVisitorWrapper entryVisitorWrapper =
                new CountingEntryVisitorWrapper(traceVisitor);
        transaction.accept(captureTick, entryVisitorWrapper);

        Profile mainThreadProfile = transaction.getMainThreadProfileProtobuf();
        if (mainThreadProfile != null) {
            traceVisitor.visitMainThreadProfile(mainThreadProfile);
        }
        long mainThreadProfileSampleCount = getProfileSampleCount(mainThreadProfile);
        // mainThreadProfile can be gc'd at this point

        Profile auxThreadProfile = transaction.getAuxThreadProfileProtobuf();
        if (auxThreadProfile != null) {
            traceVisitor.visitAuxThreadProfile(auxThreadProfile);
        }
        long auxThreadProfileSampleCount = getProfileSampleCount(auxThreadProfile);
        // auxThreadProfile can be gc'd at this point

        Trace.Header header = createTraceHeader(transaction, slow, partial, captureTime,
                captureTick, entryVisitorWrapper.count, mainThreadProfileSampleCount,
                auxThreadProfileSampleCount);
        traceVisitor.visitHeader(header);
    }

    private static Trace.Header createTraceHeader(Transaction transaction, boolean slow,
            boolean partial, long captureTime, long captureTick, int entryCount,
            long mainProfileSampleCount, long auxProfileSampleCount) {
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

    private abstract static class TraceReaderImpl implements TraceReader {

        private final long captureTime;
        private final String traceId;
        private final boolean partial;
        private final boolean update;

        private TraceReaderImpl(long captureTime, String traceId, boolean partial, boolean update) {
            this.captureTime = captureTime;
            this.traceId = traceId;
            this.partial = partial;
            this.update = update;
        }

        @Override
        public long captureTime() {
            return captureTime;
        }

        @Override
        public String traceId() {
            return traceId;
        }

        @Override
        public boolean partial() {
            return partial;
        }

        @Override
        public boolean update() {
            return update;
        }
    }

    private static class CountingEntryVisitor implements EntryVisitor {

        private int count;

        @Override
        public int visitSharedQueryText(String sharedQueryText) {
            return 0;
        }

        @Override
        public void visitEntry(Trace.Entry entry) {
            if (countEntry(entry)) {
                count++;
            }
        }

        private static boolean countEntry(Trace.Entry entry) {
            // don't count "auxiliary thread" entries since those are not counted in
            // maxTraceEntriesPerTransaction limit (and it's confusing when entry count exceeds the
            // limit)
            return !entry.getMessage().equals(Transaction.AUXILIARY_THREAD_MESSAGE);
        }
    }

    private static class CountingEntryVisitorWrapper implements EntryVisitor {

        private final @Nullable EntryVisitor delegate;
        private int count;

        private CountingEntryVisitorWrapper(@Nullable EntryVisitor delegate) {
            this.delegate = delegate;
        }

        @Override
        public int visitSharedQueryText(String sharedQueryText) throws Exception {
            if (delegate == null) {
                return 0;
            } else {
                return delegate.visitSharedQueryText(sharedQueryText);
            }
        }

        @Override
        public void visitEntry(Trace.Entry entry) {
            if (CountingEntryVisitor.countEntry(entry)) {
                count++;
            }
            if (delegate != null) {
                delegate.visitEntry(entry);
            }
        }
    }
}
