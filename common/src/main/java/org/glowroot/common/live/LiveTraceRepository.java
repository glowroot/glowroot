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

import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public interface LiveTraceRepository {

    @Nullable
    Trace.Header getHeader(String serverId, String traceId) throws IOException;

    List<Trace.Entry> getEntries(String serverId, String traceId) throws IOException;

    @Nullable
    Profile getProfile(String serverId, String traceId) throws IOException;

    @Nullable
    Trace getFullTrace(String serverId, String traceId) throws IOException;

    int getMatchingTraceCount(String serverId, String transactionType,
            @Nullable String transactionName);

    List<TracePoint> getMatchingActiveTracePoints(TraceKind traceKind, String serverId,
            String transactionType, @Nullable String transactionName, TracePointCriteria criteria,
            int limit, long captureTime, long captureTick);

    List<TracePoint> getMatchingPendingPoints(TraceKind traceKind, String serverId,
            String transactionType, @Nullable String transactionName, TracePointCriteria criteria,
            long captureTime);

    @Value.Immutable
    public abstract static class TracePointCriteria {
        public abstract long durationNanosLow();
        public abstract @Nullable Long durationNanosHigh();
        public abstract @Nullable StringComparator transactionNameComparator();
        public abstract @Nullable String transactionName();
        public abstract @Nullable StringComparator headlineComparator();
        public abstract @Nullable String headline();
        public abstract @Nullable StringComparator errorMessageComparator();
        public abstract @Nullable String errorMessage();
        public abstract @Nullable StringComparator userComparator();
        public abstract @Nullable String user();
        public abstract @Nullable String attributeName();
        public abstract @Nullable StringComparator attributeValueComparator();
        public abstract @Nullable String attributeValue();
    }

    @Value.Immutable
    public interface TracePoint {
        String serverId();
        String traceId();
        long captureTime();
        long durationNanos();
        boolean error();
    }

    public enum TraceKind {
        SLOW, ERROR;
    }

    public enum Existence {
        YES, NO, EXPIRED;
    }

    public class LiveTraceRepositoryNop implements LiveTraceRepository {

        @Override
        public @Nullable Trace.Header getHeader(String serverId, String traceId) {
            return null;
        }

        @Override
        public List<Trace.Entry> getEntries(String serverId, String traceId) {
            return ImmutableList.of();
        }

        @Override
        public @Nullable Profile getProfile(String serverId, String traceId) {
            return null;
        }

        @Override
        public @Nullable Trace getFullTrace(String serverId, String traceId) {
            return null;
        }

        @Override
        public int getMatchingTraceCount(String serverId, String transactionType,
                @Nullable String transactionName) {
            return 0;
        }

        @Override
        public List<TracePoint> getMatchingActiveTracePoints(TraceKind traceKind, String serverId,
                String transactionType, @Nullable String transactionName,
                TracePointCriteria criteria, int limit, long captureTime, long captureTick) {
            return ImmutableList.of();
        }

        @Override
        public List<TracePoint> getMatchingPendingPoints(TraceKind traceKind, String serverId,
                String transactionType, @Nullable String transactionName,
                TracePointCriteria criteria, long captureTime) {
            return ImmutableList.of();
        }
    }
}
