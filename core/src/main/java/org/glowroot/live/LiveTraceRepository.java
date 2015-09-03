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
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.collector.spi.model.ProfileTreeOuterClass.ProfileTree;
import org.glowroot.collector.spi.model.TraceOuterClass.Trace;
import org.glowroot.markers.OnlyUsedByTests;

public interface LiveTraceRepository {

    @Nullable
    Trace.Header getHeader(String traceId) throws IOException;

    List<Trace.Entry> getEntries(String traceId) throws IOException;

    @Nullable
    ProfileTree getProfileTree(String traceId) throws IOException;

    @Nullable
    Trace getFullTrace(String traceId) throws IOException;

    int getMatchingTraceCount(String transactionType, @Nullable String transactionName);

    List<TracePoint> getMatchingActiveTracePoints(long captureTime, long captureTick,
            TracePointQuery query);

    List<TracePoint> getMatchingPendingPoints(long captureTime, TracePointQuery query);

    @OnlyUsedByTests
    int getTransactionCount();

    @OnlyUsedByTests
    int getPendingTransactionCount();

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

    public enum Existence {
        YES, NO, EXPIRED;
    }

    public class LiveTraceRepositoryNop implements LiveTraceRepository {

        @Override
        public @Nullable Trace.Header getHeader(String traceId) {
            return null;
        }

        @Override
        public List<Trace.Entry> getEntries(String traceId) {
            return ImmutableList.of();
        }

        @Override
        public @Nullable ProfileTree getProfileTree(String traceId) {
            return null;
        }

        @Override
        public @Nullable Trace getFullTrace(String traceId) {
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
