/*
 * Copyright 2015-2017 the original author or authors.
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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import org.glowroot.agent.collector.Collector.EntryVisitor;
import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.agent.collector.Collector.TraceVisitor;
import org.glowroot.agent.impl.TraceCreator;
import org.glowroot.agent.impl.Transaction;
import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.common.live.ImmutableEntries;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

public class LiveTraceRepositoryImpl implements LiveTraceRepository {

    private static final String AGENT_ID = "";

    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;
    private final Clock clock;
    private final Ticker ticker;

    public LiveTraceRepositoryImpl(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, Clock clock, Ticker ticker) {
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
        this.clock = clock;
        this.ticker = ticker;
    }

    // checks active traces first, then pending traces (and finally caller should check stored
    // traces) to make sure that the trace is not missed if it is in transition between these states
    @Override
    public @Nullable Trace.Header getHeader(String agentId, String traceId) throws Exception {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return createTraceHeader(transaction);
            }
        }
        return null;
    }

    @Override
    public @Nullable Entries getEntries(String agentId, String traceId) throws Exception {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                // FIXME stream to central, similar to collectTrace
                CollectingEntryVisitor entryVisitor = new CollectingEntryVisitor();
                transaction.accept(ticker.read(), entryVisitor);
                return ImmutableEntries.builder()
                        .addAllEntries(entryVisitor.entries)
                        .addAllSharedQueryTexts(TraceCreator.toProto(entryVisitor.sharedQueryTexts))
                        .build();
            }
        }
        return null;
    }

    @Override
    public @Nullable Profile getMainThreadProfile(String agentId, String traceId)
            throws IOException {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return transaction.getMainThreadProfileProtobuf();
            }
        }
        return null;
    }

    @Override
    public @Nullable Profile getAuxThreadProfile(String agentId, String traceId)
            throws IOException {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return transaction.getAuxThreadProfileProtobuf();
            }
        }
        return null;
    }

    @Override
    public @Nullable Trace getFullTrace(String agentId, String traceId) throws Exception {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                // FIXME stream to central, similar to collectTrace
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
                        .addAllEntry(traceVisitor.getEntries())
                        .addAllSharedQueryText(
                                TraceCreator.toProto(traceVisitor.getSharedQueryTexts()))
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
            // don't include partially stored traces since those are already counted above
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
        for (Transaction transaction : transactionCollector.getPendingTransactions()) {
            if (matches(transaction, traceKind, transactionType, transactionName, filter)) {
                points.add(ImmutableTracePoint.builder()
                        .agentId(AGENT_ID)
                        .traceId(transaction.getTraceId())
                        .captureTime(captureTime)
                        .durationNanos(transaction.getDurationNanos())
                        .partial(false)
                        .error(transaction.getErrorMessage() != null)
                        .build());
            }
        }
        return points;
    }

    @VisibleForTesting
    boolean matchesActive(Transaction transaction, String transactionType,
            @Nullable String transactionName) {
        if (!transactionCollector.shouldStoreSlow(transaction)) {
            return false;
        }
        if (!transactionType.equals(transaction.getTransactionType())) {
            return false;
        }
        if (transactionName != null && !transactionName.equals(transaction.getTransactionName())) {
            return false;
        }
        return true;
    }

    private Trace.Header createTraceHeader(Transaction transaction) throws Exception {
        // capture time before checking if complete to guard against condition where partial
        // trace header is created with captureTime > the real (completed) capture time
        long captureTime = clock.currentTimeMillis();
        long captureTick = ticker.read();
        if (transaction.isCompleted()) {
            return TraceCreator.createCompletedTraceHeader(transaction);
        } else {
            return TraceCreator.createPartialTraceHeader(transaction, captureTime, captureTick);
        }
    }

    private TraceReader createTraceReader(Transaction transaction) {
        if (transaction.isCompleted()) {
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
                && filter.matchesHeadline(transaction.getHeadline())
                && filter.matchesError(errorMessage == null ? "" : errorMessage.message())
                && filter.matchesUser(transaction.getUser())
                && filter.matchesAttributes(transaction.getAttributes().asMap());
    }

    private boolean matchesKind(Transaction transaction, TraceKind traceKind) {
        if (traceKind == TraceKind.SLOW) {
            return transactionCollector.shouldStoreSlow(transaction);
        } else {
            // TraceKind.ERROR
            return transactionCollector.shouldStoreError(transaction);
        }
    }

    private static boolean matchesTransactionType(Transaction transaction, String transactionType) {
        return transactionType.equals(transaction.getTransactionType());
    }

    private static boolean matchesTransactionName(Transaction transaction,
            @Nullable String transactionName) {
        return transactionName == null || transactionName.equals(transaction.getTransactionName());
    }

    private static class CollectingEntryVisitor implements EntryVisitor {

        private final Map<String, Integer> sharedQueryTextIndexes = Maps.newHashMap();
        private final List<String> sharedQueryTexts = Lists.newArrayList();

        private final List<Trace.Entry> entries = Lists.newArrayList();

        @Override
        public int visitSharedQueryText(String sharedQueryText) {
            Integer sharedQueryTextIndex = sharedQueryTextIndexes.get(sharedQueryText);
            if (sharedQueryTextIndex == null) {
                sharedQueryTextIndex = sharedQueryTextIndexes.size();
                sharedQueryTextIndexes.put(sharedQueryText, sharedQueryTextIndex);
                sharedQueryTexts.add(sharedQueryText);
            }
            return sharedQueryTextIndex;
        }

        @Override
        public void visitEntry(Trace.Entry entry) {
            entries.add(entry);
        }

        List<Trace.Entry> getEntries() {
            return entries;
        }

        List<String> getSharedQueryTexts() {
            return sharedQueryTexts;
        }
    }

    private static class CollectingTraceVisitor extends CollectingEntryVisitor
            implements TraceVisitor {

        private @Nullable Profile mainThreadProfile;
        private @Nullable Profile auxThreadProfile;
        private @Nullable Trace.Header header;

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
}
