/*
 * Copyright 2012-2018 the original author or authors.
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
import java.util.Map;

import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.agent.collector.Collector.TraceVisitor;
import org.glowroot.agent.impl.Transaction.TraceEntryVisitor;
import org.glowroot.agent.model.DetailMapWriter;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.agent.model.MergedThreadTimer;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile.ProfileNode;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

@Styles.Private
public class TraceCreator {

    private TraceCreator() {}

    public static TraceReader createTraceReaderForPartial(Transaction transaction, long captureTime,
            long captureTick) {
        return new TraceReaderImpl(transaction, true, captureTime, captureTick,
                transaction.getTraceId(), true, transaction.isPartiallyStored());
    }

    public static TraceReader createTraceReaderForCompleted(Transaction transaction, boolean slow) {
        return new TraceReaderImpl(transaction, slow, transaction.getCaptureTime(),
                transaction.getEndTick(), transaction.getTraceId(), false,
                transaction.isPartiallyStored());
    }

    public static Trace.Header createPartialTraceHeader(Transaction transaction, long captureTime,
            long captureTick) {
        int entryCount = transaction.getEntryCount(captureTick);
        int queryCount = transaction.getQueryCount();
        long mainThreadProfileSampleCount = transaction.getMainThreadProfileSampleCount();
        long auxThreadProfileSampleCount = transaction.getAuxThreadProfileSampleCount();
        // only slow transactions reach this point, so setting slow=true (second arg below)
        return createTraceHeader(transaction, true, true, captureTime, captureTick,
                entryCount, queryCount, mainThreadProfileSampleCount, auxThreadProfileSampleCount);
    }

    public static Trace.Header createCompletedTraceHeader(Transaction transaction) {
        int entryCount = transaction.getEntryCount(transaction.getEndTick());
        int queryCount = transaction.getQueryCount();
        long mainProfileSampleCount = transaction.getMainThreadProfileSampleCount();
        long auxProfileSampleCount = transaction.getAuxThreadProfileSampleCount();
        // only slow transactions reach this point, so setting slow=true (second arg below)
        return createTraceHeader(transaction, true, false, transaction.getCaptureTime(),
                transaction.getEndTick(), entryCount, queryCount, mainProfileSampleCount,
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

    private static Trace.Header createTraceHeader(Transaction transaction, boolean slow,
            boolean partial, long captureTime, long captureTick, int entryCount,
            int queryCount, long mainThreadProfileSampleCount, long auxThreadProfileSampleCount) {
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
        for (Map.Entry<String, Collection<String>> entry : transaction.getAttributes().asMap()
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
        ThreadStatsCollectorImpl mainThreadStats = new ThreadStatsCollectorImpl();
        mainThreadStats.mergeThreadStats(transaction.getMainThreadStats());
        builder.setMainThreadStats(mainThreadStats.toProto());
        if (transaction.hasAuxThreadContexts()) {
            MergedThreadTimer auxThreadRootTimer = MergedThreadTimer.createAuxThreadRootTimer();
            transaction.mergeAuxThreadTimersInto(auxThreadRootTimer);
            builder.setAuxThreadRootTimer(auxThreadRootTimer.toProto());
            ThreadStatsCollectorImpl auxThreadStats = new ThreadStatsCollectorImpl();
            transaction.mergeAuxThreadStatsInto(auxThreadStats);
            builder.setAuxThreadStats(auxThreadStats.toProto());
        }
        RootTimerCollectorImpl asyncTimers = new RootTimerCollectorImpl();
        transaction.mergeAsyncTimersInto(asyncTimers);
        builder.addAllAsyncTimer(asyncTimers.toProto());
        addCounts(builder, transaction, entryCount, queryCount,
                mainThreadProfileSampleCount, auxThreadProfileSampleCount);
        return builder.build();
    }

    private static void addCounts(Trace.Header.Builder builder, Transaction transaction,
            int entryCount, int queryCount, long mainThreadProfileSampleCount,
            long auxThreadProfileSampleCount) {
        builder.setEntryCount(entryCount);
        builder.setEntryLimitExceeded(transaction.isEntryLimitExceeded(entryCount));
        builder.setQueryCount(queryCount);
        builder.setQueryLimitExceeded(transaction.isQueryLimitExceeded(queryCount));
        builder.setMainThreadProfileSampleCount(mainThreadProfileSampleCount);
        builder.setMainThreadProfileSampleLimitExceeded(
                transaction.isMainThreadProfileSampleLimitExceeded(mainThreadProfileSampleCount));
        builder.setAuxThreadProfileSampleCount(auxThreadProfileSampleCount);
        builder.setAuxThreadProfileSampleLimitExceeded(
                transaction.isAuxThreadProfileSampleLimitExceeded(auxThreadProfileSampleCount));
    }

    private static class TraceReaderImpl implements TraceReader {

        private final Transaction transaction;
        private final boolean slow;
        private final long captureTime;
        private final long captureTick;
        private final String traceId;
        private final boolean partial;
        private final boolean update;

        private Trace. /*@Nullable*/ Header header;

        private TraceReaderImpl(Transaction transaction, boolean slow, long captureTime,
                long captureTick, String traceId, boolean partial, boolean update) {
            this.transaction = transaction;
            this.slow = slow;
            this.captureTime = captureTime;
            this.captureTick = captureTick;
            this.traceId = traceId;
            this.partial = partial;
            this.update = update;
        }

        @Override
        public void accept(TraceVisitor traceVisitor) throws Exception {
            // timings for traces that are still active are normalized to the capture tick in order
            // to *attempt* to present a picture of the trace at that exact tick
            // (without using synchronization to block updates to the trace while it is being read)
            CountingEntryVisitorWrapper entryVisitorWrapper =
                    new CountingEntryVisitorWrapper(traceVisitor);
            transaction.visitEntries(captureTick, entryVisitorWrapper);

            List<Aggregate.Query> queries = transaction.getQueries();
            traceVisitor.visitQueries(queries);

            traceVisitor.visitSharedQueryTexts(transaction.getSharedQueryTexts());

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

            int entryCount = entryVisitorWrapper.count;
            int queryCount = queries.size();

            if (header == null) {
                traceVisitor.visitHeader(createTraceHeader(transaction, slow, partial, captureTime,
                        captureTick, entryCount, queryCount, mainThreadProfileSampleCount,
                        auxThreadProfileSampleCount));
            } else {
                Trace.Header.Builder builder = header.toBuilder();
                addCounts(builder, transaction, entryCount, queryCount,
                        mainThreadProfileSampleCount, auxThreadProfileSampleCount);
                traceVisitor.visitHeader(builder.build());
            }
        }

        @Override
        public Trace.Header readHeader() {
            if (header == null) {
                header = createTraceHeader(transaction, true, partial, captureTime, captureTick, 0,
                        0, 0, 0);
            }
            return header;
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

    private static class CountingEntryVisitorWrapper implements TraceEntryVisitor {

        private final TraceVisitor delegate;
        private int count;

        private CountingEntryVisitorWrapper(TraceVisitor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void visitEntry(Trace.Entry entry) {
            if (countEntry(entry)) {
                count++;
            }
            delegate.visitEntry(entry);
        }

        private static boolean countEntry(Trace.Entry entry) {
            // don't count "auxiliary thread" entries since those are not counted in
            // maxTraceEntriesPerTransaction limit (and it's confusing when entry count exceeds the
            // limit)
            return !entry.getMessage().equals(Transaction.AUXILIARY_THREAD_MESSAGE);
        }
    }
}
