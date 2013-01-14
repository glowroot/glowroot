/**
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

import static org.fest.assertions.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.informant.core.trace.Trace;
import io.informant.core.trace.TraceRegistry;
import io.informant.core.util.Clock;
import io.informant.local.trace.TraceSinkLocal;
import io.informant.local.trace.TraceSnapshotDao;
import io.informant.local.trace.TraceSnapshotDao.StringComparator;
import io.informant.local.trace.TraceSnapshotPoint;
import io.informant.local.trace.TraceSnapshotService;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

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
        List<TraceSnapshotPoint> points = Lists.newArrayList();
        points.add(mockPoint("id1", 123, 500, false));
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(1);
        assertThat(response.getNormalTraces().size()).isEqualTo(0);
    }

    @Test
    public void shouldReturnCompletedStoredTraceInPlaceOfActiveTrace() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        activeTraces.add(mockActiveTrace("id1", 500));
        List<Trace> pendingTraces = Lists.newArrayList();
        List<TraceSnapshotPoint> points = Lists.newArrayList();
        points.add(mockPoint("id1", 123, 500, true));
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(0);
        assertThat(response.getNormalTraces().size()).isEqualTo(1);
    }

    @Test
    public void shouldReturnCompletedPendingTraceInPlaceOfActiveTrace() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        activeTraces.add(mockActiveTrace("id1", 500));
        List<Trace> pendingTraces = Lists.newArrayList();
        pendingTraces.add(mockPendingTrace("id1", 500, true));
        List<TraceSnapshotPoint> points = Lists.newArrayList();
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(0);
        assertThat(response.getNormalTraces().size()).isEqualTo(1);
    }

    // this is relevant because completed pending traces don't have firm capturedAt
    // and non-completed pending traces don't have firm capturedAt or duration
    @Test
    public void shouldReturnStoredTraceInPlaceOfPendingTrace() throws IOException {
        // given
        List<Trace> activeTraces = Lists.newArrayList();
        List<Trace> pendingTraces = Lists.newArrayList();
        pendingTraces.add(mockPendingTrace("id1", 500, true));
        List<TraceSnapshotPoint> points = Lists.newArrayList();
        points.add(mockPoint("id1", 10001, 500, true));
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points, 10000, DEFAULT_CURRENT_TICK);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(0);
        assertThat(response.getNormalTraces().size()).isEqualTo(1);
        assertThat(response.getNormalTraces().get(0).getCapturedAt()).isEqualTo(10001);
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
        List<TraceSnapshotPoint> points = Lists.newArrayList();
        for (int i = 200; i < 300; i++) {
            points.add(mockPoint("id" + i, 1, random.nextInt(1000), true));
        }
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":1000}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(100);
        assertThat(response.getNormalTraces().size()).isEqualTo(200);
        assertThat(response.getActiveTraces()).isSorted();
        assertThat(response.getNormalTraces()).isSorted();
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
        List<TraceSnapshotPoint> points = Lists.newArrayList();
        TracePointJsonService tracePointJsonService = buildTracePointJsonService(activeTraces,
                pendingTraces, points);
        // when
        String responseJson = tracePointJsonService
                .getPoints("{\"from\":0,\"to\":0,\"limit\":100}");
        // then
        TracePointResponse response = TracePointResponse.from(responseJson);
        assertThat(response.getActiveTraces().size()).isEqualTo(100);
        assertThat(response.getNormalTraces().size()).isEqualTo(0);
    }

    private static TracePointJsonService buildTracePointJsonService(List<Trace> activeTraces,
            List<Trace> pendingTraces, List<TraceSnapshotPoint> points) {

        return buildTracePointJsonService(activeTraces, pendingTraces, points,
                DEFAULT_CURRENT_TIME_MILLIS, DEFAULT_CURRENT_TICK);
    }

    private static TracePointJsonService buildTracePointJsonService(List<Trace> activeTraces,
            List<Trace> pendingTraces, List<TraceSnapshotPoint> points, long currentTimeMillis,
            long currentTick) {

        Ordering<TraceSnapshotPoint> durationDescOrdering = Ordering.natural().reverse()
                .onResultOf(new Function<TraceSnapshotPoint, Double>() {
                    public Double apply(TraceSnapshotPoint trace) {
                        return trace.getDuration();
                    }
                });

        List<TraceSnapshotPoint> orderedPoints = durationDescOrdering.sortedCopy(points);

        TraceSnapshotDao traceSnapshotDao = mock(TraceSnapshotDao.class);
        TraceRegistry traceRegistry = mock(TraceRegistry.class);
        TraceSinkLocal traceSinkLocal = mock(TraceSinkLocal.class);
        TraceSnapshotService traceSnapshotService = mock(TraceSnapshotService.class);
        Ticker ticker = mock(Ticker.class);
        Clock clock = mock(Clock.class);

        when(traceSnapshotDao.readPoints(anyLong(), anyLong(), anyLong(), anyLong(),
                anyBoolean(), anyBoolean(), anyBoolean(), any(StringComparator.class), anyString(),
                any(StringComparator.class), anyString(), anyInt())).thenReturn(orderedPoints);
        when(traceRegistry.getTraces()).thenReturn(activeTraces);
        // for now, assume all active traces will be stored
        when(traceSnapshotService.shouldStore(any(Trace.class))).thenReturn(true);
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

    private TraceSnapshotPoint mockPoint(String id, long capturedAt, long durationMillis,
            boolean completed) {
        return TraceSnapshotPoint.from(id, capturedAt,
                TimeUnit.MILLISECONDS.toNanos(durationMillis), completed, false);
    }

    private static class TracePointResponse {
        private final List<TracePoint> normalTraces;
        private final List<TracePoint> errorTraces;
        private final List<TracePoint> activeTraces;
        private static TracePointResponse from(String responseJson) {
            JsonObject points = new Gson().fromJson(responseJson, JsonElement.class)
                    .getAsJsonObject();
            JsonArray normalPointsJson = points.get("normalPoints").getAsJsonArray();
            List<TracePoint> normalTraces = Lists.newArrayList();
            for (int i = 0; i < normalPointsJson.size(); i++) {
                JsonArray normalPointJson = normalPointsJson.get(i).getAsJsonArray();
                normalTraces.add(TracePoint.from(normalPointJson));
            }
            JsonArray errorPointsJson = points.get("errorPoints").getAsJsonArray();
            List<TracePoint> errorTraces = Lists.newArrayList();
            for (int i = 0; i < errorPointsJson.size(); i++) {
                JsonArray errorPointJson = errorPointsJson.get(i).getAsJsonArray();
                errorTraces.add(TracePoint.from(errorPointJson));
            }
            JsonArray activePointsJson = points.get("activePoints").getAsJsonArray();
            List<TracePoint> activeTraces = Lists.newArrayList();
            for (int i = 0; i < activePointsJson.size(); i++) {
                JsonArray activePointJson = activePointsJson.get(i).getAsJsonArray();
                activeTraces.add(TracePoint.from(activePointJson));
            }
            return new TracePointResponse(normalTraces, errorTraces, activeTraces);
        }
        private TracePointResponse(List<TracePoint> normalTraces, List<TracePoint> errorTraces,
                List<TracePoint> activeTraces) {
            this.normalTraces = normalTraces;
            this.errorTraces = errorTraces;
            this.activeTraces = activeTraces;
        }
        private List<TracePoint> getNormalTraces() {
            return normalTraces;
        }
        public List<TracePoint> getErrorTraces() {
            return errorTraces;
        }
        private List<TracePoint> getActiveTraces() {
            return activeTraces;
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
