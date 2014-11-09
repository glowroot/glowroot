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
package org.glowroot.local.ui;

import java.sql.SQLException;
import java.util.List;
import java.util.Random;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.junit.Test;

import org.glowroot.collector.TransactionCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.local.store.ImmutableTracePoint;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.TraceDao;
import org.glowroot.local.store.TracePoint;
import org.glowroot.local.store.TracePointQuery;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TracePointJsonServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Random random = new Random();

    private static final long DEFAULT_CURRENT_TICK = random.nextLong();
    private static final long DEFAULT_CURRENT_TIME_MILLIS = Math.abs(random.nextLong());

    // mostly the interesting tests are when requesting to=0 so active & pending traces are included

    @Test
    public void shouldReturnCompletedStoredPointInPlaceOfActivePoint() throws Exception {
        // given
        List<Transaction> activeTransactions = Lists.newArrayList();
        activeTransactions.add(mockActiveTransaction("id1", 500));
        List<Transaction> pendingTransactions = Lists.newArrayList();
        List<TracePoint> points = Lists.newArrayList();
        points.add(mockPoint("id1", 123, 500));
        TracePointJsonService tracePointJsonService =
                buildTracePointJsonService(activeTransactions, pendingTransactions, points);
        // when
        String content = tracePointJsonService.getPoints("from=0&to=0&duration-low=0&limit=100");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.activePoints().size()).isEqualTo(0);
        assertThat(response.normalPoints().size()).isEqualTo(1);
    }

    @Test
    public void shouldReturnCompletedPendingPointInPlaceOfActivePoint() throws Exception {
        // given
        List<Transaction> activeTransactions = Lists.newArrayList();
        activeTransactions.add(mockActiveTransaction("id1", 500));
        List<Transaction> pendingTransactions = Lists.newArrayList();
        pendingTransactions.add(mockPendingTransaction("id1", 500));
        List<TracePoint> points = Lists.newArrayList();
        TracePointJsonService tracePointJsonService =
                buildTracePointJsonService(activeTransactions, pendingTransactions, points);
        // when
        String content = tracePointJsonService.getPoints("from=0&to=0&duration-low=0&limit=100");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.activePoints().size()).isEqualTo(0);
        assertThat(response.normalPoints().size()).isEqualTo(1);
    }

    // this is relevant because completed pending traces don't have firm end times
    // and non-completed pending traces don't have firm end times or durations
    @Test
    public void shouldReturnStoredTraceInPlaceOfPendingTrace() throws Exception {
        // given
        List<Transaction> activeTransactions = Lists.newArrayList();
        List<Transaction> pendingTransactions = Lists.newArrayList();
        pendingTransactions.add(mockPendingTransaction("id1", 500));
        List<TracePoint> points = Lists.newArrayList();
        points.add(mockPoint("id1", 10001, 500));
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(
                activeTransactions, pendingTransactions, points, 10000, DEFAULT_CURRENT_TICK);
        // when
        String content = tracePointJsonService.getPoints("from=0&to=0&duration-low=0&limit=100");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.activePoints().size()).isEqualTo(0);
        assertThat(response.normalPoints().size()).isEqualTo(1);
        assertThat(response.normalPoints().get(0).captureTime()).isEqualTo(10001);
    }

    @Test
    public void shouldReturnOrderedByDurationDesc() throws Exception {
        // given
        List<Transaction> activeTransactions = Lists.newArrayList();
        for (int i = 0; i < 100; i++) {
            activeTransactions.add(mockActiveTransaction("id" + i, random.nextInt(1000)));
        }
        List<Transaction> pendingTransactions = Lists.newArrayList();
        for (int i = 100; i < 200; i++) {
            pendingTransactions.add(mockPendingTransaction("id" + i, random.nextInt(1000)));
        }
        List<TracePoint> points = Lists.newArrayList();
        for (int i = 200; i < 300; i++) {
            points.add(mockPoint("id" + i, 1, random.nextInt(1000)));
        }
        TracePointJsonService tracePointJsonService =
                buildTracePointJsonService(activeTransactions, pendingTransactions, points);
        // when
        String content = tracePointJsonService.getPoints("from=0&to=0&duration-low=0&limit=1000");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.activePoints().size()).isEqualTo(100);
        assertThat(response.normalPoints().size()).isEqualTo(200);
        assertThat(response.activePoints()).isSorted();
        assertThat(response.normalPoints()).isSorted();
    }

    @Test
    public void shouldNotReturnMoreThanRequestedLimit() {

    }

    @Test
    public void shouldHandleCaseWithMoreActiveTracesThanLimit() throws Exception {
        // given
        List<Transaction> activeTransactions = Lists.newArrayList();
        for (int i = 0; i < 110; i++) {
            activeTransactions.add(mockActiveTransaction("id" + i, 500));
        }
        List<Transaction> pendingTransactions = Lists.newArrayList();
        List<TracePoint> points = Lists.newArrayList();
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(
                activeTransactions, pendingTransactions, points);
        // when
        String content = tracePointJsonService.getPoints("from=0&to=0&duration-low=0&limit=100");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.activePoints().size()).isEqualTo(100);
        assertThat(response.normalPoints().size()).isEqualTo(0);
    }

    private static TracePointJsonService buildTracePointJsonService(
            List<Transaction> activeTransactions, List<Transaction> pendingTransactions,
            List<TracePoint> points) throws SQLException {

        return buildTracePointJsonService(activeTransactions, pendingTransactions, points,
                DEFAULT_CURRENT_TIME_MILLIS, DEFAULT_CURRENT_TICK);
    }

    private static TracePointJsonService buildTracePointJsonService(
            List<Transaction> activeTransactions, List<Transaction> pendingTransactions,
            List<TracePoint> points, long currentTimeMillis,
            long currentTick) throws SQLException {

        Ordering<TracePoint> durationDescOrdering = Ordering.natural().reverse()
                .onResultOf(new Function<TracePoint, Double>() {
                    @Override
                    public Double apply(TracePoint trace) {
                        return trace.duration();
                    }
                });

        ImmutableList<TracePoint> orderedPoints = durationDescOrdering.immutableSortedCopy(points);
        QueryResult<TracePoint> queryResult = new QueryResult<TracePoint>(orderedPoints, false);

        TraceDao traceDao = mock(TraceDao.class);
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        TransactionCollectorImpl transactionCollector = mock(TransactionCollectorImpl.class);
        Ticker ticker = mock(Ticker.class);
        Clock clock = mock(Clock.class);

        when(traceDao.readPoints(any(TracePointQuery.class))).thenReturn(queryResult);
        when(transactionRegistry.getTransactions()).thenReturn(activeTransactions);
        // for now, assume all active traces will be stored
        when(transactionCollector.shouldStore(any(Transaction.class))).thenReturn(true);
        when(transactionCollector.getPendingCompleteTransactions()).thenReturn(pendingTransactions);
        when(ticker.read()).thenReturn(currentTick);
        when(clock.currentTimeMillis()).thenReturn(currentTimeMillis);

        return new TracePointJsonService(traceDao, transactionRegistry, transactionCollector,
                ticker, clock);
    }

    private static Transaction mockActiveTransaction(String id, long durationMillis) {
        Transaction transaction = mock(Transaction.class);
        when(transaction.getId()).thenReturn(id);
        when(transaction.getStartTick()).thenReturn(
                DEFAULT_CURRENT_TICK - MILLISECONDS.toNanos(durationMillis));
        when(transaction.getCustomAttributes())
                .thenReturn(ImmutableSetMultimap.<String, String>of());
        return transaction;
    }

    private static Transaction mockPendingTransaction(String id, long durationMillis) {
        Transaction transaction = mock(Transaction.class);
        when(transaction.getId()).thenReturn(id);
        when(transaction.getDuration()).thenReturn(MILLISECONDS.toNanos(durationMillis));
        when(transaction.isCompleted()).thenReturn(true);
        when(transaction.getCustomAttributes())
                .thenReturn(ImmutableSetMultimap.<String, String>of());
        return transaction;
    }

    private static TracePoint mockPoint(String id, long end, long durationMillis) {
        return ImmutableTracePoint.builder()
                .id(id)
                .captureTime(end)
                .duration(MILLISECONDS.toNanos(durationMillis))
                .error(false)
                .build();
    }
}
