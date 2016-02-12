/*
 * Copyright 2015-2016 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.agent.model.TraceCreator;
import org.glowroot.agent.model.Transaction;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

public class LiveTraceRepositoryImpl implements LiveTraceRepository {

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
    public @Nullable Trace.Header getHeader(String agentId, String traceId) throws IOException {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return createTraceHeader(transaction);
            }
        }
        return null;
    }

    // this is only called if the trace does have traces, so empty list response means trace was not
    // found (e.g. has expired)
    @Override
    public List<Trace.Entry> getEntries(String agentId, String traceId) {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return transaction.getEntriesProtobuf(ticker.read());
            }
        }
        return ImmutableList.of();
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
    public @Nullable Trace getFullTrace(String agentId, String traceId) throws IOException {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getTraceId().equals(traceId)) {
                return createFullTrace(transaction);
            }
        }
        return null;
    }

    @Override
    public int getMatchingTraceCount(String agentId, String transactionType,
            @Nullable String transactionName) {
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
    public List<TracePoint> getMatchingActiveTracePoints(TraceKind traceKind, String agentId,
            String transactionType, @Nullable String transactionName, TracePointFilter filter,
            int limit, long captureTime, long captureTick) {
        List<TracePoint> activeTracePoints = Lists.newArrayList();
        for (Transaction transaction : transactionRegistry.getTransactions()) {
            long startTick = transaction.getStartTick();
            if (matches(transaction, traceKind, transactionType, transactionName, filter)
                    && startTick < captureTick) {
                activeTracePoints.add(ImmutableTracePoint.builder()
                        .agentId(agentId)
                        .traceId(transaction.getTraceId())
                        .captureTime(captureTime)
                        .durationNanos(captureTick - startTick)
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
    public List<TracePoint> getMatchingPendingPoints(TraceKind traceKind, String agentId,
            String transactionType, @Nullable String transactionName, TracePointFilter filter,
            long captureTime) {
        List<TracePoint> points = Lists.newArrayList();
        for (Transaction transaction : transactionCollector.getPendingTransactions()) {
            if (matches(transaction, traceKind, transactionType, transactionName, filter)) {
                points.add(ImmutableTracePoint.builder()
                        .agentId(agentId)
                        .traceId(transaction.getTraceId())
                        .captureTime(captureTime)
                        .durationNanos(transaction.getDurationNanos())
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

    private Trace.Header createTraceHeader(Transaction transaction) throws IOException {
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

    private Trace createFullTrace(Transaction transaction) throws IOException {
        if (transaction.isCompleted()) {
            return TraceCreator.createCompletedTrace(transaction, true);
        } else {
            return TraceCreator.createPartialTrace(transaction, clock.currentTimeMillis(),
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

    private boolean matchesTransactionType(Transaction transaction, String transactionType) {
        return transactionType.equals(transaction.getTransactionType());
    }

    private boolean matchesTransactionName(Transaction transaction,
            @Nullable String transactionName) {
        return transactionName == null || transactionName.equals(transaction.getTransactionName());
    }
}
