/*
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.ui;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.junit.Test;

import io.informant.collector.TraceCollectorImpl;
import io.informant.common.Clock;
import io.informant.common.ObjectMappers;
import io.informant.local.store.SnapshotDao;
import io.informant.local.store.SnapshotDao.StringComparator;
import io.informant.local.store.TracePoint;
import io.informant.trace.TraceRegistry;
import io.informant.trace.model.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.fest.assertions.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TracePointJsonServiceTest {

    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final Random random = new Random();
    private static final long DEFAULT_CURRENT_TICK = random.nextLong();
    private static final long DEFAULT_CURRENT_TIME_MILLIS = Math.abs(random.nextLong());

    // mostly the interesting tests are when requesting to=0 so active & pending traces are included

    @Test
    public void shouldReturnCompletedStoredPointInPlaceOfActivePoint() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        activeTraces.add(mockActiveTrace("id1", 500));
        List<Trace> pendingTraces = Lists.newArrayList();
        List<TracePoint> points = Lists.newArrayList();
        points.add(mockPoint("id1", 123, 500));
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points);
        // when
        String content = tracePointJsonService.getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.getActivePoints().size()).isEqualTo(0);
        assertThat(response.getNormalPoints().size()).isEqualTo(1);
    }

    @Test
    public void shouldReturnCompletedPendingPointInPlaceOfActivePoint() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        activeTraces.add(mockActiveTrace("id1", 500));
        List<Trace> pendingTraces = Lists.newArrayList();
        pendingTraces.add(mockPendingTrace("id1", 500));
        List<TracePoint> points = Lists.newArrayList();
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points);
        // when
        String content = tracePointJsonService.getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.getActivePoints().size()).isEqualTo(0);
        assertThat(response.getNormalPoints().size()).isEqualTo(1);
    }

    // this is relevant because completed pending traces don't have firm end times
    // and non-completed pending traces don't have firm end times or durations
    @Test
    public void shouldReturnStoredTraceInPlaceOfPendingTrace() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        List<Trace> pendingTraces = Lists.newArrayList();
        pendingTraces.add(mockPendingTrace("id1", 500));
        List<TracePoint> points = Lists.newArrayList();
        points.add(mockPoint("id1", 10001, 500));
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points, 10000, DEFAULT_CURRENT_TICK);
        // when
        String content = tracePointJsonService.getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.getActivePoints().size()).isEqualTo(0);
        assertThat(response.getNormalPoints().size()).isEqualTo(1);
        assertThat(response.getNormalPoints().get(0).getCaptureTime()).isEqualTo(10001);
    }

    @Test
    public void shouldReturnOrderedByDurationDesc() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        for (int i = 0; i < 100; i++) {
            activeTraces.add(mockActiveTrace("id" + i, random.nextInt(1000)));
        }
        List<Trace> pendingTraces = Lists.newArrayList();
        for (int i = 100; i < 200; i++) {
            pendingTraces.add(mockPendingTrace("id" + i, random.nextInt(1000)));
        }
        List<TracePoint> points = Lists.newArrayList();
        for (int i = 200; i < 300; i++) {
            points.add(mockPoint("id" + i, 1, random.nextInt(1000)));
        }
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points);
        // when
        String content = tracePointJsonService.getPoints("{\"from\":0,\"to\":0,\"limit\":1000}");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.getActivePoints().size()).isEqualTo(100);
        assertThat(response.getNormalPoints().size()).isEqualTo(200);
        assertThat(response.getActivePoints()).isSorted();
        assertThat(response.getNormalPoints()).isSorted();
    }

    @Test
    public void shouldNotReturnMoreThanRequestedLimit() {

    }

    @Test
    public void shouldHandleCaseWithMoreActiveTracesThanLimit() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        for (int i = 0; i < 110; i++) {
            activeTraces.add(mockActiveTrace("id" + i, 500));
        }
        List<Trace> pendingTraces = Lists.newArrayList();
        List<TracePoint> points = Lists.newArrayList();
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points);
        // when
        String content = tracePointJsonService.getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        assertThat(response.getActivePoints().size()).isEqualTo(100);
        assertThat(response.getNormalPoints().size()).isEqualTo(0);
    }

    private static TracePointJsonService buildTracePointJsonService(List<Trace> activeTraces,
            List<Trace> pendingTraces, List<TracePoint> points) {

        return buildTracePointJsonService(activeTraces, pendingTraces, points,
                DEFAULT_CURRENT_TIME_MILLIS, DEFAULT_CURRENT_TICK);
    }

    private static TracePointJsonService buildTracePointJsonService(List<Trace> activeTraces,
            List<Trace> pendingTraces, List<TracePoint> points, long currentTimeMillis,
            long currentTick) {

        Ordering<TracePoint> durationDescOrdering = Ordering.natural().reverse()
                .onResultOf(new Function<TracePoint, Double>() {
                    public Double apply(TracePoint trace) {
                        return trace.getDuration();
                    }
                });

        List<TracePoint> orderedPoints = durationDescOrdering.sortedCopy(points);

        SnapshotDao snapshotDao = mock(SnapshotDao.class);
        TraceRegistry traceRegistry = mock(TraceRegistry.class);
        TraceCollectorImpl traceCollector = mock(TraceCollectorImpl.class);
        Ticker ticker = mock(Ticker.class);
        Clock clock = mock(Clock.class);

        when(snapshotDao.readNonStuckPoints(anyLong(), anyLong(), anyLong(), anyLong(),
                anyBoolean(), anyBoolean(), anyBoolean(), any(StringComparator.class), anyString(),
                any(StringComparator.class), anyString(), anyInt())).thenReturn(orderedPoints);
        when(traceRegistry.getTraces()).thenReturn(activeTraces);
        // for now, assume all active traces will be stored
        when(traceCollector.shouldStore(any(Trace.class))).thenReturn(true);
        when(traceCollector.getPendingCompleteTraces()).thenReturn(pendingTraces);
        when(ticker.read()).thenReturn(currentTick);
        when(clock.currentTimeMillis()).thenReturn(currentTimeMillis);

        return new TracePointJsonService(snapshotDao, traceRegistry, traceCollector,
                ticker, clock);
    }

    private static Trace mockActiveTrace(String id, long durationMillis) {
        Trace trace = mock(Trace.class);
        when(trace.getId()).thenReturn(id);
        when(trace.getStartTick()).thenReturn(
                DEFAULT_CURRENT_TICK - MILLISECONDS.toNanos(durationMillis));
        return trace;
    }

    private static Trace mockPendingTrace(String id, long durationMillis) {
        Trace trace = mock(Trace.class);
        when(trace.getId()).thenReturn(id);
        when(trace.getDuration()).thenReturn(MILLISECONDS.toNanos(durationMillis));
        when(trace.isCompleted()).thenReturn(true);
        return trace;
    }

    private TracePoint mockPoint(String id, long end, long durationMillis) {
        return TracePoint.from(id, end, MILLISECONDS.toNanos(durationMillis), false);
    }
}
