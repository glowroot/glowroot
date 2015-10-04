/*
 * Copyright 2015 the original author or authors.
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.ProfileTreeOuterClass.ProfileTree;
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
    public @Nullable Trace.Header getHeader(String server, String traceId) throws IOException {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return createTraceHeader(transaction);
            }
        }
        return null;
    }

    @Override
    public List<Trace.Entry> getEntries(String server, String traceId) {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return transaction.getEntriesProtobuf();
            }
        }
        return ImmutableList.of();
    }

    @Override
    public @Nullable ProfileTree getProfileTree(String server, String traceId)
            throws IOException {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return transaction.getProfileTreeProtobuf();
            }
        }
        return null;
    }

    @Override
    public @Nullable Trace getFullTrace(String server, String traceId) throws IOException {
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return createFullTrace(transaction);
            }
        }
        return null;
    }

    @Override
    public int getMatchingTraceCount(String server, String transactionType,
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
    public List<TracePoint> getMatchingActiveTracePoints(String server, long captureTime,
            long captureTick, TracePointQuery query) {
        List<TracePoint> activeTracePoints = Lists.newArrayList();
        for (Transaction transaction : transactionRegistry.getTransactions()) {
            long startTick = transaction.getStartTick();
            if (matches(transaction, query) && startTick < captureTick) {
                activeTracePoints.add(ImmutableTracePoint.builder()
                        .server(server)
                        .traceId(transaction.getId())
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
        if (query.limit() != 0 && activeTracePoints.size() > query.limit()) {
            activeTracePoints = activeTracePoints.subList(0, query.limit());
        }
        return activeTracePoints;
    }

    @Override
    public List<TracePoint> getMatchingPendingPoints(String server, long captureTime,
            TracePointQuery query) {
        List<TracePoint> points = Lists.newArrayList();
        for (Transaction transaction : transactionCollector.getPendingTransactions()) {
            if (matches(transaction, query)) {
                points.add(ImmutableTracePoint.builder()
                        .server(server)
                        .traceId(transaction.getId())
                        .captureTime(captureTime)
                        .durationNanos(transaction.getDurationNanos())
                        .error(transaction.getErrorMessage() != null)
                        .build());
            }
        }
        return points;
    }

    @Override
    @OnlyUsedByTests
    public int getTransactionCount(String server) {
        return transactionRegistry.getTransactions().size();
    }

    @Override
    @OnlyUsedByTests
    public int getPendingTransactionCount(String server) {
        return transactionCollector.getPendingTransactions().size();
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

    private boolean matches(Transaction transaction, TracePointQuery query) {
        return matchesTotal(transaction, query)
                && matchesTransactionType(transaction, query)
                && matchesSlowOnly(transaction, query)
                && matchesErrorOnly(transaction, query)
                && matchesHeadline(transaction, query)
                && matchesTransactionName(transaction, query)
                && matchesError(transaction, query)
                && matchesUser(transaction, query)
                && matchesAttribute(transaction, query);
    }

    private boolean matchesTotal(Transaction transaction, TracePointQuery query) {
        long totalNanos = transaction.getDurationNanos();
        if (totalNanos < query.durationNanosLow()) {
            return false;
        }
        Long totalNanosHigh = query.durationNanosHigh();
        return totalNanosHigh == null || totalNanos <= totalNanosHigh;
    }

    private boolean matchesTransactionType(Transaction transaction, TracePointQuery query) {
        String transactionType = query.transactionType();
        if (Strings.isNullOrEmpty(transactionType)) {
            return true;
        }
        return transactionType.equals(transaction.getTransactionType());
    }

    private boolean matchesSlowOnly(Transaction transaction, TracePointQuery query) {
        return !query.slowOnly() || transactionCollector.shouldStoreSlow(transaction);
    }

    private boolean matchesErrorOnly(Transaction transaction, TracePointQuery query) {
        return !query.errorOnly() || transactionCollector.shouldStoreError(transaction);
    }

    private boolean matchesHeadline(Transaction transaction, TracePointQuery query) {
        return matchesUsingStringComparator(query.headlineComparator(), query.headline(),
                transaction.getHeadline());
    }

    private boolean matchesTransactionName(Transaction transaction, TracePointQuery query) {
        return matchesUsingStringComparator(query.transactionNameComparator(),
                query.transactionName(), transaction.getTransactionName());
    }

    private boolean matchesError(Transaction transaction, TracePointQuery query) {
        ErrorMessage errorMessage = transaction.getErrorMessage();
        String text = errorMessage == null ? "" : errorMessage.message();
        return matchesUsingStringComparator(query.errorComparator(), query.error(), text);
    }

    private boolean matchesUser(Transaction transaction, TracePointQuery query) {
        return matchesUsingStringComparator(query.userComparator(), query.user(),
                transaction.getUser());
    }

    private boolean matchesAttribute(Transaction transaction, TracePointQuery query) {
        if (Strings.isNullOrEmpty(query.attributeName())
                && (query.attributeValueComparator() == null
                        || Strings.isNullOrEmpty(query.attributeValue()))) {
            // no custom attribute filter
            return true;
        }
        ImmutableMap<String, Collection<String>> attributes = transaction.getAttributes().asMap();
        for (Entry<String, Collection<String>> entry : attributes.entrySet()) {
            String attributeName = entry.getKey();
            if (!matchesUsingStringComparator(StringComparator.EQUALS, query.attributeName(),
                    attributeName)) {
                // name doesn't match, no need to test values
                continue;
            }
            for (String attributeValue : entry.getValue()) {
                if (matchesUsingStringComparator(query.attributeValueComparator(),
                        query.attributeValue(), attributeValue)) {
                    // found matching name and value
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesUsingStringComparator(@Nullable StringComparator requestComparator,
            @Nullable String requestText, String traceText) throws AssertionError {
        if (requestComparator == null || Strings.isNullOrEmpty(requestText)) {
            return true;
        }
        return requestComparator.matches(traceText, requestText);
    }
}
