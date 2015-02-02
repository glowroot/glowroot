/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;

import org.glowroot.collector.TransactionCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.local.store.ImmutableTracePoint;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.StringComparator;
import org.glowroot.local.store.TraceDao;
import org.glowroot.local.store.TracePoint;
import org.glowroot.local.store.TracePointQuery;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.transaction.model.Transaction;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class TracePointJsonService {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private final TraceDao traceDao;
    private final TransactionRegistry transactionRegistry;
    private final TransactionCollectorImpl transactionCollector;
    private final Ticker ticker;
    private final Clock clock;

    TracePointJsonService(TraceDao traceDao, TransactionRegistry transactionRegistry,
            TransactionCollectorImpl transactionCollector, Ticker ticker, Clock clock) {
        this.traceDao = traceDao;
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
        this.ticker = ticker;
        this.clock = clock;
    }

    @GET("/backend/trace/points")
    String getPoints(String queryString) throws Exception {
        TracePointQuery query = QueryStrings.decode(queryString, TracePointQuery.class);
        return new Handler(query).handle();
    }

    private class Handler {

        private final TracePointQuery query;

        public Handler(TracePointQuery request) {
            this.query = request;
        }

        private String handle() throws Exception {
            boolean captureActiveTraces = shouldCaptureActiveTraces();
            List<Transaction> activeTraces = Lists.newArrayList();
            long captureTime = 0;
            long captureTick = 0;
            if (captureActiveTraces) {
                // capture active traces first to make sure that none are missed in the transition
                // between active and pending/stored (possible duplicates are removed below)
                activeTraces = getMatchingActiveTraces();
                // take capture timings after the capture to make sure there are no traces captured
                // that start after the recorded capture time (resulting in negative duration)
                captureTime = clock.currentTimeMillis();
                captureTick = ticker.read();
            }
            QueryResult<TracePoint> queryResult = getStoredAndPendingPoints(captureActiveTraces);
            List<TracePoint> points = queryResult.records();
            removeDuplicatesBetweenActiveTracesAndPoints(activeTraces, points);
            return writeResponse(points, activeTraces, captureTime, captureTick,
                    queryResult.moreAvailable());
        }

        private boolean shouldCaptureActiveTraces() {
            long currentTimeMillis = clock.currentTimeMillis();
            return (query.to() == 0 || query.to() > currentTimeMillis)
                    && query.from() < currentTimeMillis;
        }

        private QueryResult<TracePoint> getStoredAndPendingPoints(boolean captureActiveTraces)
                throws SQLException {
            List<TracePoint> matchingPendingPoints;
            // it only seems worth looking at pending traces if request asks for active traces
            if (captureActiveTraces) {
                // important to grab pending traces before stored points to ensure none are
                // missed in the transition between pending and stored
                matchingPendingPoints = getMatchingPendingPoints();
            } else {
                matchingPendingPoints = ImmutableList.of();
            }
            QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
            // create single merged and limited list of points
            List<TracePoint> orderedPoints = Lists.newArrayList(queryResult.records());
            for (TracePoint pendingPoint : matchingPendingPoints) {
                insertIntoOrderedPoints(pendingPoint, orderedPoints);
            }
            return new QueryResult<TracePoint>(orderedPoints, queryResult.moreAvailable());
        }

        private List<Transaction> getMatchingActiveTraces() {
            List<Transaction> activeTraces = Lists.newArrayList();
            for (Transaction transaction : transactionRegistry.getTransactions()) {
                if (matches(transaction)) {
                    activeTraces.add(transaction);
                }
            }
            Collections.sort(activeTraces,
                    Ordering.natural().onResultOf(new Function<Transaction, Long>() {
                        @Override
                        public Long apply(@Nullable Transaction transaction) {
                            // sorting activeTraces which is List<@NonNull Transaction>
                            checkNotNull(transaction);
                            return transaction.getStartTick();
                        }
                    }));
            if (activeTraces.size() > query.limit()) {
                activeTraces = activeTraces.subList(0, query.limit());
            }
            return activeTraces;
        }

        private List<TracePoint> getMatchingPendingPoints() {
            List<TracePoint> points = Lists.newArrayList();
            for (Transaction transaction : transactionCollector.getPendingTransactions()) {
                if (matchesDuration(transaction)
                        && matchesTransactionType(transaction)
                        && matchesErrorOnly(transaction)
                        && matchesHeadline(transaction)
                        && matchesTransactionName(transaction)
                        && matchesError(transaction)
                        && matchesUser(transaction)
                        && matchesCustomAttribute(transaction)) {
                    ImmutableTracePoint point = ImmutableTracePoint.builder()
                            .id(transaction.getId())
                            .captureTime(clock.currentTimeMillis())
                            .duration(transaction.getDuration())
                            .error(transaction.getError() != null)
                            .build();
                    points.add(point);
                }
            }
            return points;
        }

        private boolean matches(Transaction transaction) {
            return transactionCollector.shouldStore(transaction)
                    && matchesDuration(transaction)
                    && matchesTransactionType(transaction)
                    && matchesErrorOnly(transaction)
                    && matchesHeadline(transaction)
                    && matchesTransactionName(transaction)
                    && matchesError(transaction)
                    && matchesUser(transaction)
                    && matchesCustomAttribute(transaction);
        }

        private boolean matchesDuration(Transaction transaction) {
            long duration = transaction.getDuration();
            if (duration < query.durationLow()) {
                return false;
            }
            Long durationHigh = query.durationHigh();
            return durationHigh == null || duration <= durationHigh;
        }

        private boolean matchesTransactionType(Transaction transaction) {
            String transactionType = query.transactionType();
            if (Strings.isNullOrEmpty(transactionType)) {
                return true;
            }
            return transactionType.equals(transaction.getTransactionType());
        }

        private boolean matchesErrorOnly(Transaction transaction) {
            return !query.errorOnly() || transaction.getError() != null;
        }

        private boolean matchesHeadline(Transaction transaction) {
            return matchesUsingStringComparator(query.headlineComparator(), query.headline(),
                    transaction.getHeadline());
        }

        private boolean matchesTransactionName(Transaction transaction) {
            return matchesUsingStringComparator(query.transactionNameComparator(),
                    query.transactionName(), transaction.getTransactionName());
        }

        private boolean matchesError(Transaction transaction) {
            return matchesUsingStringComparator(query.errorComparator(), query.error(),
                    transaction.getError());
        }

        private boolean matchesUser(Transaction transaction) {
            return matchesUsingStringComparator(query.userComparator(), query.user(),
                    transaction.getUser());
        }

        private boolean matchesCustomAttribute(Transaction transaction) {
            if (Strings.isNullOrEmpty(query.customAttributeName())
                    && (query.customAttributeValueComparator() == null
                    || Strings.isNullOrEmpty(query.customAttributeValue()))) {
                // no custom attribute filter
                return true;
            }
            ImmutableMap<String, Collection<String>> customAttributes =
                    transaction.getCustomAttributes().asMap();
            for (Entry<String, Collection<String>> entry : customAttributes.entrySet()) {
                String customAttributeName = entry.getKey();
                if (!matchesUsingStringComparator(StringComparator.EQUALS,
                        query.customAttributeName(), customAttributeName)) {
                    // name doesn't match, no need to test values
                    continue;
                }
                for (String customAttributeValue : entry.getValue()) {
                    if (matchesUsingStringComparator(query.customAttributeValueComparator(),
                            query.customAttributeValue(), customAttributeValue)) {
                        // found matching name and value
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean matchesUsingStringComparator(@Nullable StringComparator requestComparator,
                @Nullable String requestText, @Nullable String traceText) throws AssertionError {
            if (requestComparator == null || Strings.isNullOrEmpty(requestText)) {
                return true;
            } else if (Strings.isNullOrEmpty(traceText)) {
                return false;
            }
            return requestComparator.matches(traceText, requestText);
        }

        private void insertIntoOrderedPoints(TracePoint pendingPoint,
                List<TracePoint> orderedPoints) {
            int duplicateIndex = -1;
            int insertionIndex = -1;
            // check if duplicate and capture insertion index at the same time
            for (int i = 0; i < orderedPoints.size(); i++) {
                TracePoint point = orderedPoints.get(i);
                if (pendingPoint.id().equals(point.id())) {
                    duplicateIndex = i;
                    break;
                }
                if (pendingPoint.duration() > point.duration()) {
                    insertionIndex = i;
                    break;
                }
            }
            if (duplicateIndex != -1) {
                TracePoint point = orderedPoints.get(duplicateIndex);
                if (pendingPoint.duration() > point.duration()) {
                    // prefer the pending trace, it must be a partial trace that has just completed
                    orderedPoints.set(duplicateIndex, pendingPoint);
                }
                return;
            }
            if (insertionIndex == -1) {
                orderedPoints.add(pendingPoint);
            } else {
                orderedPoints.add(insertionIndex, pendingPoint);
            }
        }

        private void removeDuplicatesBetweenActiveTracesAndPoints(List<Transaction> activeTraces,
                List<TracePoint> points) {
            for (Iterator<Transaction> i = activeTraces.iterator(); i.hasNext();) {
                Transaction activeTransaction = i.next();
                for (Iterator<TracePoint> j = points.iterator(); j.hasNext();) {
                    TracePoint point = j.next();
                    if (!activeTransaction.getId().equals(point.id())) {
                        continue;
                    }
                    if (activeTransaction.getDuration() > point.duration()) {
                        // prefer the active trace, it must be a partial trace that hasn't
                        // completed yet
                        j.remove();
                    } else {
                        // otherwise prefer the completed trace
                        i.remove();
                    }
                    // there can be at most one duplicate per id, so ok to break to outer
                    break;
                }
            }
        }

        private String writeResponse(List<TracePoint> points, List<Transaction> activeTraces,
                long captureTime, long captureTick, boolean limitExceeded) throws IOException,
                SQLException {
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeArrayFieldStart("normalPoints");
            for (TracePoint point : points) {
                if (!point.error()) {
                    jg.writeStartArray();
                    jg.writeNumber(point.captureTime());
                    jg.writeNumber(point.duration() / 1000000000.0);
                    jg.writeString(point.id());
                    jg.writeEndArray();
                }
            }
            jg.writeEndArray();
            jg.writeArrayFieldStart("errorPoints");
            for (TracePoint point : points) {
                if (point.error()) {
                    jg.writeStartArray();
                    jg.writeNumber(point.captureTime());
                    jg.writeNumber(point.duration() / 1000000000.0);
                    jg.writeString(point.id());
                    jg.writeEndArray();
                }
            }
            jg.writeEndArray();
            jg.writeArrayFieldStart("activePoints");
            for (Transaction activeTrace : activeTraces) {
                jg.writeStartArray();
                jg.writeNumber(captureTime);
                jg.writeNumber((captureTick - activeTrace.getStartTick()) / 1000000000.0);
                jg.writeString(activeTrace.getId());
                jg.writeEndArray();
            }
            jg.writeEndArray();
            if (limitExceeded) {
                jg.writeBooleanField("limitExceeded", true);
            }
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
    }
}
