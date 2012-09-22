/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.local.ui;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.util.Clock;
import org.informantproject.local.trace.TraceSinkLocal;
import org.informantproject.local.trace.TraceSnapshotDao;
import org.informantproject.local.trace.TraceSnapshotDao.StringComparator;
import org.informantproject.local.trace.TraceSnapshotService;
import org.informantproject.local.trace.TraceSnapshotSummary;
import org.junit.Test;

import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TracePointJsonServiceTest {

    private static final Random random = new Random();
    private static final long DEFAULT_CURRENT_TICK = random.nextLong();
    private static final long DEFAULT_CURRENT_TIME_MILLIS = Math.abs(random.nextLong());

    // mostly the interesting tests are when requesting to=0 so active & pending traces are included

    @Test
    public void shouldReturnActiveTraceInPlaceOfNotCompletedStoredTrace() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        activeTraces.add(mockActiveTrace("id1", 500));
        List<Trace> pendingTraces = Lists.newArrayList();
        List<TraceSnapshotSummary> storedSummaries = Lists.newArrayList();
        storedSummaries.add(mockStoredSummary("id1", 123, 500, false));
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, storedSummaries);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(1);
        assertThat(response.getStoredTraces().size()).isEqualTo(0);
    }

    @Test
    public void shouldReturnCompletedStoredTraceInPlaceOfActiveTrace() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        activeTraces.add(mockActiveTrace("id1", 500));
        List<Trace> pendingTraces = Lists.newArrayList();
        List<TraceSnapshotSummary> storedSummaries = Lists.newArrayList();
        storedSummaries.add(mockStoredSummary("id1", 123, 500, true));
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, storedSummaries);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(0);
        assertThat(response.getStoredTraces().size()).isEqualTo(1);
    }

    @Test
    public void shouldReturnCompletedPendingTraceInPlaceOfActiveTrace() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        activeTraces.add(mockActiveTrace("id1", 500));
        List<Trace> pendingTraces = Lists.newArrayList();
        pendingTraces.add(mockPendingTrace("id1", 500, true));
        List<TraceSnapshotSummary> storedSummaries = Lists.newArrayList();
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, storedSummaries);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(0);
        assertThat(response.getStoredTraces().size()).isEqualTo(1);
    }

    // this is relevant because completed pending traces don't have firm capturedAt
    // and non-completed pending traces don't have firm capturedAt or duration
    @Test
    public void shouldReturnStoredTraceInPlaceOfPendingTrace() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        List<Trace> pendingTraces = Lists.newArrayList();
        pendingTraces.add(mockPendingTrace("id1", 500, true));
        List<TraceSnapshotSummary> storedSummaries = Lists.newArrayList();
        storedSummaries.add(mockStoredSummary("id1", 10001, 500, true));
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, storedSummaries, 10000, DEFAULT_CURRENT_TICK);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(0);
        assertThat(response.getStoredTraces().size()).isEqualTo(1);
        assertThat(response.getStoredTraces().get(0).getCapturedAt()).isEqualTo(10001);
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
            pendingTraces.add(mockPendingTrace("id" + i, random.nextInt(1000), true));
        }
        List<TraceSnapshotSummary> storedSummaries = Lists.newArrayList();
        for (int i = 200; i < 300; i++) {
            storedSummaries.add(mockStoredSummary("id" + i, 1, random.nextInt(1000), true));
        }
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, storedSummaries);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":1000}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(100);
        assertThat(response.getStoredTraces().size()).isEqualTo(200);
        assertThat(response.getActiveTraces()).isSorted();
        assertThat(response.getStoredTraces()).isSorted();
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
        List<TraceSnapshotSummary> storedSummaries = Lists.newArrayList();
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, storedSummaries);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(100);
        assertThat(response.getStoredTraces().size()).isEqualTo(0);
    }

    private static TracePointJsonService buildTracePointJsonService(List<Trace> activeTraces,
            List<Trace> pendingTraces, List<TraceSnapshotSummary> storedSummaries) {
        return buildTracePointJsonService(activeTraces, pendingTraces, storedSummaries,
                DEFAULT_CURRENT_TIME_MILLIS, DEFAULT_CURRENT_TICK);
    }

    private static TracePointJsonService buildTracePointJsonService(List<Trace> activeTraces,
            List<Trace> pendingTraces, List<TraceSnapshotSummary> storedSummaries,
            long currentTimeMillis, long currentTick) {

        Ordering<TraceSnapshotSummary> durationDescOrdering = Ordering.natural().reverse()
                .onResultOf(new Function<TraceSnapshotSummary, Double>() {
                    public Double apply(@Nullable TraceSnapshotSummary trace) {
                        return trace.getDuration();
                    }
                });

        List<TraceSnapshotSummary> orderedStoredSummaries = durationDescOrdering
                .sortedCopy(storedSummaries);

        TraceSnapshotDao traceSnapshotDao = mock(TraceSnapshotDao.class);
        TraceRegistry traceRegistry = mock(TraceRegistry.class);
        TraceSinkLocal traceSinkLocal = mock(TraceSinkLocal.class);
        TraceSnapshotService traceSnapshotService = mock(TraceSnapshotService.class);
        Ticker ticker = mock(Ticker.class);
        Clock clock = mock(Clock.class);

        when(traceSnapshotDao.readSummaries(anyLong(), anyLong(), anyLong(), anyLong(),
                anyBoolean(), anyBoolean(), anyBoolean(), any(StringComparator.class), anyString(),
                anyInt())).thenReturn(orderedStoredSummaries);
        when(traceRegistry.getTraces()).thenReturn(activeTraces);
        // for now, assume all active traces will be persisted
        when(traceSnapshotService.shouldPersist(any(Trace.class))).thenReturn(true);
        when(traceSinkLocal.getPendingCompleteTraces()).thenReturn(pendingTraces);
        when(ticker.read()).thenReturn(currentTick);
        when(clock.currentTimeMillis()).thenReturn(currentTimeMillis);

        return new TracePointJsonService(traceSnapshotDao, traceRegistry, traceSinkLocal,
                traceSnapshotService, ticker, clock);
    }
    private static Trace mockActiveTrace(String id, long durationMillis) {
        Trace trace = mock(Trace.class);
        when(trace.getId()).thenReturn(id);
        when(trace.getStartTick()).thenReturn(
                DEFAULT_CURRENT_TICK - TimeUnit.MILLISECONDS.toNanos(durationMillis));
        return trace;
    }

    private static Trace mockPendingTrace(String id, long durationMillis, boolean completed) {
        Trace trace = mock(Trace.class);
        when(trace.getId()).thenReturn(id);
        when(trace.getDuration()).thenReturn(TimeUnit.MILLISECONDS.toNanos(durationMillis));
        when(trace.isCompleted()).thenReturn(completed);
        return trace;
    }

    private TraceSnapshotSummary mockStoredSummary(String id, long capturedAt, long durationMillis,
            boolean completed) {
        return TraceSnapshotSummary.from(id, capturedAt,
                TimeUnit.MILLISECONDS.toNanos(durationMillis), completed);
    }

    private static class TracePointResponse {
        private final List<TracePoint> activeTraces;
        private final List<TracePoint> storedTraces;
        private static TracePointResponse from(String responseJson) {
            JsonObject points = new Gson().fromJson(responseJson, JsonElement.class)
                    .getAsJsonObject();
            JsonArray activePointsJson = points.get("activePoints").getAsJsonArray();
            List<TracePoint> activeTraces = Lists.newArrayList();
            for (int i = 0; i < activePointsJson.size(); i++) {
                JsonArray activePointJson = activePointsJson.get(i).getAsJsonArray();
                activeTraces.add(TracePoint.from(activePointJson));
            }
            JsonArray storedPointsJson = points.get("storedPoints").getAsJsonArray();
            List<TracePoint> storedTraces = Lists.newArrayList();
            for (int i = 0; i < storedPointsJson.size(); i++) {
                JsonArray storedPointJson = storedPointsJson.get(i).getAsJsonArray();
                storedTraces.add(TracePoint.from(storedPointJson));
            }
            return new TracePointResponse(activeTraces, storedTraces);
        }
        private TracePointResponse(List<TracePoint> activeTraces, List<TracePoint> storedTraces) {
            this.activeTraces = activeTraces;
            this.storedTraces = storedTraces;
        }
        private List<TracePoint> getActiveTraces() {
            return activeTraces;
        }
        private List<TracePoint> getStoredTraces() {
            return storedTraces;
        }
    }

    private static class TracePoint implements Comparable<TracePoint> {
        private final long capturedAt;
        private final double durationSeconds;
        private final String id;
        private static TracePoint from(JsonArray point) {
            long capturedAt = point.get(0).getAsLong();
            double durationSeconds = point.get(1).getAsDouble();
            String id = point.get(2).getAsString();
            return new TracePoint(capturedAt, durationSeconds, id);
        }
        private TracePoint(long capturedAt, double durationSeconds, String id) {
            this.capturedAt = capturedAt;
            this.durationSeconds = durationSeconds;
            this.id = id;
        }
        private long getCapturedAt() {
            return capturedAt;
        }
        private double getDurationSeconds() {
            return durationSeconds;
        }
        private String getId() {
            return id;
        }
        // natural sort order is by duration desc
        public int compareTo(TracePoint o) {
            return Double.compare(o.durationSeconds, durationSeconds);
        }
    }
}
