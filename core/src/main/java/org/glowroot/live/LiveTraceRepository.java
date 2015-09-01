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
package org.glowroot.live;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.collector.spi.GarbageCollectorActivity;
import org.glowroot.collector.spi.ThrowableInfo;
import org.glowroot.collector.spi.TraceTimerNode;
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
    public interface TraceHeader {
        String id();
        abstract boolean active();
        // "partial" means "partial stored" but no longer currently active
        boolean partial();
        boolean error();
        long startTime();
        long captureTime();
        long durationNanos();
        String transactionType();
        String transactionName();
        String headline();
        @Nullable
        String user();
        Map<String, Collection<String>> customAttributes();
        Map<String, ? extends /*@Nullable*/Object> customDetail();
        @Nullable
        String errorMessage();
        @Nullable
        ThrowableInfo errorThrowable();
        TraceTimerNode rootTimer();
        long threadCpuNanos(); // -1 means N/A
        long threadBlockedNanos(); // -1 means N/A
        long threadWaitedNanos(); // -1 means N/A
        long threadAllocatedBytes(); // -1 means N/A
        List<GarbageCollectorActivity> gcActivity();

        int entryCount();
        boolean entryLimitExceeded();
        Existence entriesExistence();

        long profileSampleCount();
        boolean profileLimitExceeded();
        Existence profileExistence();
    }

    @Value.Immutable
    public abstract static class TracePointQuery {

        public abstract long from();
        public abstract long to();
        public abstract long durationNanosLow();
        public abstract @Nullable Long durationNanosHigh();
        public abstract @Nullable String transactionType();
        public abstract @Nullable StringComparator transactionNameComparator();
        public abstract @Nullable String transactionName();
        public abstract @Nullable StringComparator headlineComparator();
        public abstract @Nullable String headline();
        public abstract @Nullable StringComparator errorComparator();
        public abstract @Nullable String error();
        public abstract @Nullable StringComparator userComparator();
        public abstract @Nullable String user();
        public abstract @Nullable String customAttributeName();
        public abstract @Nullable StringComparator customAttributeValueComparator();
        public abstract @Nullable String customAttributeValue();

        @Value.Default
        public boolean slowOnly() {
            return false;
        }

        @Value.Default
        public boolean errorOnly() {
            return false;
        }

        public abstract int limit();
    }

    @Value.Immutable
    public interface TracePoint {
        String id();
        long captureTime();
        long durationNanos();
        boolean error();
    }

    @Value.Immutable
    public interface TraceExport {
        TraceHeader traceHeader();
        @Nullable
        ChunkSource entries();
        @Nullable
        ChunkSource profile();
    }

    public enum Existence {
        YES, NO, EXPIRED;
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
