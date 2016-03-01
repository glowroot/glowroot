/*
 * Copyright 2015-2016 the original author or authors.
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public interface LiveTraceRepository {

    @Nullable
    Trace.Header getHeader(String agentId, String traceId) throws Exception;

    // this is only called if the trace does have traces, so empty list response means trace was not
    // found (e.g. has expired)
    List<Trace.Entry> getEntries(String agentId, String traceId) throws Exception;

    @Nullable
    Profile getMainThreadProfile(String agentId, String traceId) throws Exception;

    @Nullable
    Profile getAuxThreadProfile(String agentId, String traceId) throws Exception;

    @Nullable
    Trace getFullTrace(String agentId, String traceId) throws Exception;

    int getMatchingTraceCount(String agentId, String transactionType,
            @Nullable String transactionName);

    List<TracePoint> getMatchingActiveTracePoints(TraceKind traceKind, String agentId,
            String transactionType, @Nullable String transactionName, TracePointFilter filter,
            int limit, long captureTime, long captureTick);

    List<TracePoint> getMatchingPendingPoints(TraceKind traceKind, String agentId,
            String transactionType, @Nullable String transactionName, TracePointFilter filter,
            long captureTime);

    @Value.Immutable
    public abstract class TracePointFilter {

        public abstract long durationNanosLow();
        public abstract @Nullable Long durationNanosHigh();
        public abstract @Nullable StringComparator headlineComparator();
        public abstract @Nullable String headline();
        public abstract @Nullable StringComparator errorMessageComparator();
        public abstract @Nullable String errorMessage();
        public abstract @Nullable StringComparator userComparator();
        public abstract @Nullable String user();
        public abstract @Nullable String attributeName();
        public abstract @Nullable StringComparator attributeValueComparator();
        public abstract @Nullable String attributeValue();

        public boolean matchesDuration(long durationNanos) {
            if (durationNanos < durationNanosLow()) {
                return false;
            }
            Long durationNanosHigh = durationNanosHigh();
            return durationNanosHigh == null || durationNanos <= durationNanosHigh;
        }

        public boolean matchesHeadline(String headline) {
            return matchesUsingStringComparator(headline, headline(), headlineComparator());
        }

        public boolean matchesError(String errorMessage) {
            return matchesUsingStringComparator(errorMessage, errorMessage(),
                    errorMessageComparator());
        }

        public boolean matchesUser(String user) {
            return matchesUsingStringComparator(user, user(), userComparator());
        }

        public boolean matchesAttributes(Map<String, ? extends Collection<String>> attributes) {
            if (Strings.isNullOrEmpty(attributeName()) && (attributeValueComparator() == null
                    || Strings.isNullOrEmpty(attributeValue()))) {
                // no custom attribute filter
                return true;
            }
            for (Entry<String, ? extends Collection<String>> entry : attributes.entrySet()) {
                String attributeName = entry.getKey();
                if (!matchesUsingStringComparator(attributeName, attributeName(),
                        StringComparator.EQUALS)) {
                    // name doesn't match, no need to test values
                    continue;
                }
                for (String attributeValue : entry.getValue()) {
                    if (matchesUsingStringComparator(attributeValue, attributeValue(),
                            attributeValueComparator())) {
                        // found matching name and value
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean matchesUsingStringComparator(String text, @Nullable String filterText,
                @Nullable StringComparator filterComparator) throws AssertionError {
            if (filterComparator == null || Strings.isNullOrEmpty(filterText)) {
                return true;
            }
            return filterComparator.matches(text, filterText);
        }
    }

    @Value.Immutable
    public interface TracePoint {
        String agentId();
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
        public @Nullable Trace.Header getHeader(String agentId, String traceId) {
            return null;
        }

        @Override
        public List<Trace.Entry> getEntries(String agentId, String traceId) {
            return ImmutableList.of();
        }

        @Override
        public @Nullable Profile getMainThreadProfile(String agentId, String traceId) {
            return null;
        }

        @Override
        public @Nullable Profile getAuxThreadProfile(String agentId, String traceId) {
            return null;
        }

        @Override
        public @Nullable Trace getFullTrace(String agentId, String traceId) {
            return null;
        }

        @Override
        public int getMatchingTraceCount(String agentId, String transactionType,
                @Nullable String transactionName) {
            return 0;
        }

        @Override
        public List<TracePoint> getMatchingActiveTracePoints(TraceKind traceKind, String agentId,
                String transactionType, @Nullable String transactionName, TracePointFilter filter,
                int limit, long captureTime, long captureTick) {
            return ImmutableList.of();
        }

        @Override
        public List<TracePoint> getMatchingPendingPoints(TraceKind traceKind, String agentId,
                String transactionType, @Nullable String transactionName, TracePointFilter filter,
                long captureTime) {
            return ImmutableList.of();
        }
    }
}
