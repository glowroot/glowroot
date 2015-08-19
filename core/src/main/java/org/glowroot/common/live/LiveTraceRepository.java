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
package org.glowroot.common.live;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.repo.TraceRepository.TraceHeader;
import org.glowroot.common.repo.TraceRepository.TracePoint;
import org.glowroot.common.repo.TraceRepository.TracePointQuery;
import org.glowroot.common.util.ChunkSource;
import org.glowroot.markers.OnlyUsedByTests;

public interface LiveTraceRepository {

    @Nullable
    TraceHeader getTraceHeader(String traceId) throws IOException;

    @Nullable
    ChunkSource getTraceEntries(String traceId) throws IOException;

    @Nullable
    ChunkSource getTraceProfile(String traceId) throws IOException;

    @Nullable
    TraceExport getTraceExport(String traceId) throws IOException;

    int getMatchingTraceCount(String transactionType, @Nullable String transactionName);

    List<TracePoint> getMatchingActiveTracePoints(long captureTime, long captureTick,
            TracePointQuery query);

    List<TracePoint> getMatchingPendingPoints(long captureTime, TracePointQuery query);

    @OnlyUsedByTests
    int getTransactionCount();

    @OnlyUsedByTests
    int getPendingTransactionCount();

    @Value.Immutable
    public interface TraceExport {
        TraceHeader traceHeader();
        @Nullable
        ChunkSource entries();
        @Nullable
        ChunkSource profile();
    }

    public class LiveTraceRepositoryNop implements LiveTraceRepository {

        @Override
        public @Nullable TraceHeader getTraceHeader(String traceId) {
            return null;
        }

        @Override
        public @Nullable ChunkSource getTraceEntries(String traceId) {
            return null;
        }

        @Override
        public @Nullable ChunkSource getTraceProfile(String traceId) {
            return null;
        }

        @Override
        public @Nullable TraceExport getTraceExport(String traceId) {
            return null;
        }

        @Override
        public int getMatchingTraceCount(String transactionType, @Nullable String transactionName) {
            return 0;
        }

        @Override
        public List<TracePoint> getMatchingActiveTracePoints(long captureTime, long captureTick,
                TracePointQuery query) {
            return ImmutableList.of();
        }

        @Override
        public List<TracePoint> getMatchingPendingPoints(long captureTime, TracePointQuery query) {
            return ImmutableList.of();
        }

        @Override
        public int getTransactionCount() {
            return 0;
        }

        @Override
        public int getPendingTransactionCount() {
            return 0;
        }
    }
}
