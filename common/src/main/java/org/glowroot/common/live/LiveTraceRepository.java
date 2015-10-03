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

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.ProfileTreeOuterClass.ProfileTree;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public interface LiveTraceRepository {

    @Nullable
    Trace.Header getHeader(String server, String traceId) throws IOException;

    List<Trace.Entry> getEntries(String server, String traceId) throws IOException;

    @Nullable
    ProfileTree getProfileTree(String server, String traceId) throws IOException;

    @Nullable
    Trace getFullTrace(String server, String traceId) throws IOException;

    int getMatchingTraceCount(String server, String transactionType,
            @Nullable String transactionName);

    List<TracePoint> getMatchingActiveTracePoints(String server, long captureTime, long captureTick,
            TracePointQuery query);

    List<TracePoint> getMatchingPendingPoints(String server, long captureTime,
            TracePointQuery query);

    @OnlyUsedByTests
    int getTransactionCount(String server);

    @OnlyUsedByTests
    int getPendingTransactionCount(String server);

    @Value.Immutable
    public abstract static class TracePointQuery {

        public abstract String serverGroup();
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
        public abstract @Nullable String attributeName();
        public abstract @Nullable StringComparator attributeValueComparator();
        public abstract @Nullable String attributeValue();

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
        String server();
        String traceId();
        long captureTime();
        long durationNanos();
        boolean error();
    }

    public enum Existence {
        YES, NO, EXPIRED;
    }

    public class LiveTraceRepositoryNop implements LiveTraceRepository {

        @Override
        public @Nullable Trace.Header getHeader(String server, String traceId) {
            return null;
        }

        @Override
        public List<Trace.Entry> getEntries(String server, String traceId) {
            return ImmutableList.of();
        }

        @Override
        public @Nullable ProfileTree getProfileTree(String server, String traceId) {
            return null;
        }

        @Override
        public @Nullable Trace getFullTrace(String server, String traceId) {
            return null;
        }

        @Override
        public int getMatchingTraceCount(String server, String transactionType,
                @Nullable String transactionName) {
            return 0;
        }

        @Override
        public List<TracePoint> getMatchingActiveTracePoints(String server, long captureTime,
                long captureTick, TracePointQuery query) {
            return ImmutableList.of();
        }

        @Override
        public List<TracePoint> getMatchingPendingPoints(String server, long captureTime,
                TracePointQuery query) {
            return ImmutableList.of();
        }

        @Override
        public int getTransactionCount(String server) {
            return 0;
        }

        @Override
        public int getPendingTransactionCount(String server) {
            return 0;
        }
    }
}
