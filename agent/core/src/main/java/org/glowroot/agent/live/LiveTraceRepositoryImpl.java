/*
 * Copyright 2015-2019 the original author or authors.
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
package org.glowroot.agent.live;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.agent.collector.Collector.TraceVisitor;
import org.glowroot.agent.impl.TraceCollector;
import org.glowroot.agent.impl.TraceCreator;
import org.glowroot.agent.impl.Transaction;
import org.glowroot.agent.impl.Transaction.TraceEntryVisitor;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.common.live.ImmutableEntries;
import org.glowroot.common.live.ImmutableQueries;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

public class LiveTraceRepositoryImpl implements LiveTraceRepository {

    private static final String AGENT_ID = "";

    private final TransactionRegistry transactionRegistry;
    private final TraceCollector traceCollector;
    private final Clock clock;
    private final Ticker ticker;

    public LiveTraceRepositoryImpl(TransactionRegistry transactionRegistry,
            TraceCollector traceCollector, Clock clock, Ticker ticker) {
        this.transactionRegistry = transactionRegistry;
        this.traceCollector = traceCollector;
        this.clock = clock;
        this.ticker = ticker;
    }

    // checks active traces first, then pending traces (and finally caller should check stored
    // traces) to make sure that the trace is not missed if it is in transition between these states
    @Override
    public Trace. /*@Nullable*/ Header getHeader(String agentId, String traceId) {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                traceCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return createTraceHeader(transaction);
            }
        }
        return null;
    }

    @Override
    public @Nullable Entries getEntries(String agentId, String traceId) {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                traceCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                CollectingEntryVisitor visitor = new CollectingEntryVisitor();
                transaction.visitEntries(ticker.read(), visitor);
                return ImmutableEntries.builder()
                        .addAllEntries(visitor.entries)
                        .addAllSharedQueryTexts(
                                TraceCreator.toProto(transaction.getSharedQueryTexts()))
                        .build();
            }
        }
        return null;
    }

    @Override
    public @Nullable Queries getQueries(String agentId, String traceId) {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                traceCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return ImmutableQueries.builder()
                        .addAllQueries(transaction.getQueries())
                        .addAllSharedQueryTexts(
                                TraceCreator.toProto(transaction.getSharedQueryTexts()))
                        .build();
            }
        }
        return null;
    }

    @Override
    public @Nullable Profile getMainThreadProfile(String agentId, String traceId) {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                traceCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return transaction.getMainThreadProfileProtobuf();
            }
        }
        return null;
    }

    @Override
    public @Nullable Profile getAuxThreadProfile(String agentId, String traceId) {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                traceCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return transaction.getAuxThreadProfileProtobuf();
            }
        }
        return null;
    }

    @Override
    public @Nullable Trace getFullTrace(String agentId, String traceId) throws Exception {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                traceCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                CollectingTraceVisitor traceVisitor = new CollectingTraceVisitor();
                TraceReader traceReader = createTraceReader(transaction);
                traceReader.accept(traceVisitor);
                Trace.Builder builder = Trace.newBuilder()
                        .setId(traceId)
                        .setUpdate(transaction.isPartiallyStored());
                Profile mainThreadProfile = traceVisitor.mainThreadProfile;
                if (mainThreadProfile != null) {
                    builder.setMainThreadProfile(mainThreadProfile);
                }
                Profile auxThreadProfile = traceVisitor.auxThreadProfile;
                if (auxThreadProfile != null) {
                    builder.setAuxThreadProfile(auxThreadProfile);
                }
                return builder.setHeader(checkNotNull(traceVisitor.header))
                        .addAllEntry(traceVisitor.entries)
                        .addAllQuery(traceVisitor.queries)
                        .addAllSharedQueryText(TraceCreator.toProto(traceVisitor.sharedQueryTexts))
                        .build();
            }
        }
        return null;
    }

    @Override
    public int getMatchingTraceCount(String transactionType, @Nullable String transactionName) {
        // include active traces, this is mostly for the case where there is just a single very
        // long running active trace and it would be misleading to display Traces (0) on the tab
        int count = 0;
        for (Transaction transaction : transactionRegistry.getTransactions()) {
            // don't include partially stored traces since no way to de-dup them with the stored
            // trace count
            if (matchesActive(transaction, transactionType, transactionName)
                    && !transaction.isPartiallyStored()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public List<TracePoint> getMatchingActiveTracePoints(TraceKind traceKind,
            String transactionType, @Nullable String transactionName, TracePointFilter filter,
            int limit, long captureTime, long captureTick) {
        List<TracePoint> activeTracePoints = Lists.newArrayList();
        for (Transaction transaction : transactionRegistry.getTransactions()) {
            long startTick = transaction.getStartTick();
            if (matches(transaction, traceKind, transactionType, transactionName, filter)
                    && startTick < captureTick) {
                activeTracePoints.add(ImmutableTracePoint.builder()
                        .agentId(AGENT_ID)
                        .traceId(transaction.getTraceId())
                        .captureTime(captureTime)
                        .durationNanos(captureTick - startTick)
                        .partial(true)
                        .error(transaction.getErrorMessage() != null)
                        .checkLiveTraces(true)
                        .build());
            }
        }
        Collections.sort(activeTracePoints,
                Ordering.natural().reverse().onResultOf(new Function<TracePoint, Long>() {
                    @Override
                    public Long apply(@Nullable TracePoint tracePoint) {
                        checkNotNull(tracePoint);
                        return tracePoint.durationNanos();
                    }
                }));
        if (limit != 0 && activeTracePoints.size() > limit) {
            activeTracePoints = activeTracePoints.subList(0, limit);
        }
        return activeTracePoints;
    }

    @Override
    public List<TracePoint> getMatchingPendingPoints(TraceKind traceKind, String transactionType,
            @Nullable String transactionName, TracePointFilter filter, long captureTime) {
        List<TracePoint> points = Lists.newArrayList();
        for (Transaction transaction : traceCollector.getPendingTransactions()) {
            if (matches(transaction, traceKind, transactionType, transactionName, filter)) {
                points.add(ImmutableTracePoint.builder()
                        .agentId(AGENT_ID)
                        .traceId(transaction.getTraceId())
                        // by the time transaction is in pending list, the capture time is set
                        .captureTime(transaction.getCaptureTime())
                        .durationNanos(transaction.getDurationNanos())
                        .partial(false)
                        .error(transaction.getErrorMessage() != null)
                        .checkLiveTraces(true)
                        .build());
            }
        }
        return points;
    }

    @Override
    public Set<String> getTransactionTypes(String agentId) {
        Set<String> transactionTypes = Sets.newHashSet();
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                traceCollector.getPendingTransactions())) {
            if (traceCollector.shouldStoreSlow(transaction)) {
                transactionTypes.add(transaction.getTransactionType());
            }
        }
        return transactionTypes;
    }

    @VisibleForTesting
    boolean matchesActive(Transaction transaction, String transactionType,
            @Nullable String transactionName) {
        if (!traceCollector.shouldStoreSlow(transaction)) {
            return false;
        }
        if (!transactionType.equals(transaction.getTransactionType())) {
            return false;
        }
        return transactionName == null || transactionName.equals(transaction.getTransactionName());
    }

    private Trace.Header createTraceHeader(Transaction transaction) {
        // capture time before checking if complete to guard against condition where partial
        // trace header is created with captureTime > the real (completed) capture time
        long captureTime = clock.currentTimeMillis();
        long captureTick = ticker.read();
        if (transaction.isFullyCompleted()) {
            return TraceCreator.createCompletedTraceHeader(transaction);
        } else {
            return TraceCreator.createPartialTraceHeader(transaction, captureTime, captureTick);
        }
    }

    private TraceReader createTraceReader(Transaction transaction) {
        if (transaction.isFullyCompleted()) {
            return TraceCreator.createTraceReaderForCompleted(transaction, true);
        } else {
            return TraceCreator.createTraceReaderForPartial(transaction, clock.currentTimeMillis(),
                    ticker.read());
        }
    }

    private boolean matches(Transaction transaction, TraceKind traceKind, String transactionType,
            @Nullable String transactionName, TracePointFilter filter) {
        ErrorMessage errorMessage = transaction.getErrorMessage();
        return matchesKind(transaction, traceKind)
                && matchesTransactionType(transaction, transactionType)
                && matchesTransactionName(transaction, transactionName)
                && filter.matchesDuration(transaction.getDurationNanos())
                && filter.matchesHeadline(transaction.getHeadline())
                && filter.matchesError(errorMessage == null ? "" : errorMessage.message())
                && filter.matchesUser(transaction.getUser())
                && filter.matchesAttributes(transaction.getAttributes().asMap());
    }

    private boolean matchesKind(Transaction transaction, TraceKind traceKind) {
        if (traceKind == TraceKind.SLOW) {
            return traceCollector.shouldStoreSlow(transaction);
        } else {
            // TraceKind.ERROR
            return traceCollector.shouldStoreError(transaction);
        }
    }

    private static boolean matchesTransactionType(Transaction transaction, String transactionType) {
        return transactionType.equals(transaction.getTransactionType());
    }

    private static boolean matchesTransactionName(Transaction transaction,
            @Nullable String transactionName) {
        return transactionName == null || transactionName.equals(transaction.getTransactionName());
    }

    private static class CollectingTraceVisitor implements TraceVisitor {

        private final List<Trace.Entry> entries = Lists.newArrayList();
        private List<Aggregate.Query> queries = ImmutableList.of();
        private List<String> sharedQueryTexts = ImmutableList.of();
        private @Nullable Profile mainThreadProfile;
        private @Nullable Profile auxThreadProfile;
        private Trace. /*@Nullable*/ Header header;

        @Override
        public void visitEntry(Trace.Entry entry) {
            entries.add(entry);
        }

        @Override
        public void visitQueries(List<Aggregate.Query> queries) {
            this.queries = queries;
        }

        @Override
        public void visitSharedQueryTexts(List<String> sharedQueryTexts) {
            this.sharedQueryTexts = sharedQueryTexts;
        }

        @Override
        public void visitMainThreadProfile(Profile profile) {
            mainThreadProfile = profile;
        }

        @Override
        public void visitAuxThreadProfile(Profile profile) {
            auxThreadProfile = profile;
        }

        @Override
        public void visitHeader(Trace.Header header) {
            this.header = header;
        }
    }

    private static class CollectingEntryVisitor implements TraceEntryVisitor {

        private final List<Trace.Entry> entries = Lists.newArrayList();

        @Override
        public void visitEntry(Trace.Entry entry) {
            entries.add(entry);
        }
    }
}
