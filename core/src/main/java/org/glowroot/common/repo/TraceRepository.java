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
package org.glowroot.common.repo;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;
import org.immutables.value.Value;

import org.glowroot.collector.spi.GarbageCollectionActivity;
import org.glowroot.collector.spi.ThrowableInfo;
import org.glowroot.collector.spi.TraceTimerNode;
import org.glowroot.common.util.Styles;

public interface TraceRepository {

    Result<TracePoint> readPoints(TracePointQuery query) throws Exception;

    long readOverallSlowCount(String transactionType, long captureTimeFrom, long captureTimeTo)
            throws Exception;

    long readTransactionSlowCount(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception;

    long readOverallErrorCount(String transactionType, long captureTimeFrom, long captureTimeTo)
            throws Exception;

    long readTransactionErrorCount(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception;

    ImmutableList<TraceErrorPoint> readErrorPoints(ErrorMessageQuery query, long resolutionMillis,
            long liveCaptureTime) throws Exception;

    Result<ErrorMessageCount> readErrorMessageCounts(ErrorMessageQuery query) throws Exception;

    @Nullable
    TraceHeader readTraceHeader(String traceId) throws Exception;

    @Nullable
    CharSource readEntries(String traceId) throws Exception;

    @Nullable
    CharSource readProfile(String traceId) throws Exception;

    // only supported by local storage implementation
    void deleteAll() throws SQLException;

    long count() throws Exception;

    @Value.Immutable
    public interface ErrorMessageCount {
        String message();
        long count();
    }

    @Value.Immutable
    public interface ErrorMessageQuery {
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
        ImmutableList<String> includes();
        ImmutableList<String> excludes();
        int limit();
    }

    @Value.Immutable
    public interface TraceHeader {
        String id();
        abstract boolean active();
        // "partial" means "partial stored" but no longer currently active
        boolean partial();
        boolean error();
        long startTime();
        long captureTime();
        long duration(); // nanoseconds
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
        long threadCpuTime(); // nanoseconds, -1 means N/A
        long threadBlockedTime(); // nanoseconds, -1 means N/A
        long threadWaitedTime(); // nanoseconds, -1 means N/A
        long threadAllocatedBytes(); // -1 means N/A
        Map<String, GarbageCollectionActivity> gcActivity();

        int entryCount();
        long profileSampleCount();
        Existence entriesExistence();
        Existence profileExistence();
    }

    @Value.Immutable
    public abstract static class TracePointQuery {

        public abstract long from();
        public abstract long to();
        public abstract long durationLow(); // nanoseconds
        public abstract @Nullable Long durationHigh(); // nanoseconds
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
        long duration();
        boolean error();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface TraceErrorPoint {
        long captureTime();
        long errorCount();
    }

    public enum Existence {
        YES, NO, EXPIRED;
    }
}
