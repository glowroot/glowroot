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
package org.glowroot.container.trace;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

import org.glowroot.container.common.HttpClient;
import org.glowroot.container.common.ObjectMappers;
import org.glowroot.container.trace.ProfileTree.ProfileNode;
import org.glowroot.container.trace.TracePointResponse.RawPoint;

import static java.util.concurrent.TimeUnit.SECONDS;

// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
public class TraceService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    static {
        mapper.registerModule(new GuavaModule());
        SimpleModule module = new SimpleModule();
        module.addAbstractTypeMapping(Trace.Error.class, ImmutableError.class);
        module.addAbstractTypeMapping(Trace.Throwable.class, ImmutableThrowable.class);
        module.addAbstractTypeMapping(Trace.Timer.class, ImmutableTimer.class);
        module.addAbstractTypeMapping(Trace.GarbageCollectionActivity.class,
                ImmutableGarbageCollectionActivity.class);
        module.addAbstractTypeMapping(Trace.Entry.class, ImmutableEntry.class);
        module.addAbstractTypeMapping(ProfileNode.class, ImmutableProfileNode.class);
        mapper.registerModule(module);
    }

    private final HttpClient httpClient;

    public TraceService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public InputStream getTraceExport(String traceId) throws Exception {
        return httpClient.getAsStream("/export/trace?server-id=0&trace-id=" + traceId);
    }

    public @Nullable Trace.Header getLastHeader() throws Exception {
        String content = httpClient.get("/backend/trace/points?server-id=0&from=0&to="
                + Long.MAX_VALUE + "&response-time-millis-low=0&limit=1000");
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        List<RawPoint> points = Lists.newArrayList();
        points.addAll(response.getNormalPoints());
        points.addAll(response.getErrorPoints());
        if (points.isEmpty()) {
            return null;
        }
        RawPoint mostRecentCapturedPoint = RawPoint.orderingByCaptureTime.max(points);
        return getHeader(mostRecentCapturedPoint.getId());
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to hit its store
    // threshold
    public @Nullable Trace.Header getActiveHeader(int timeout, TimeUnit unit) throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        Trace.Header header = null;
        // try at least once (e.g. in case timeoutMillis == 0)
        boolean first = true;
        while (first || stopwatch.elapsed(unit) < timeout) {
            header = getActiveHeader();
            if (header != null) {
                break;
            }
            Thread.sleep(20);
            first = false;
        }
        return header;
    }

    public List<Trace.Header> getHeaders(TraceQuery query) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("server-id=0&from=");
        sb.append(query.from());
        sb.append("&to=");
        sb.append(query.to());
        sb.append("&response-time-millis-low=");
        sb.append(query.responseTimeMillisLow());
        Double responseTimeMillisHigh = query.responseTimeMillisHigh();
        if (responseTimeMillisHigh != null) {
            sb.append("&response-time-millis-high=");
            sb.append(responseTimeMillisHigh);
        }
        String transactionType = query.transactionType();
        if (transactionType != null) {
            sb.append("&transaction-type=");
            sb.append(transactionType);
        }
        if (query.transactionName() != null) {
            sb.append("&transaction-name-comparator=");
            sb.append(query.transactionNameComparator());
            sb.append("&transaction-name=");
            sb.append(query.transactionName());
        }
        if (query.headline() != null) {
            sb.append("&headline-comparator=");
            sb.append(query.headlineComparator());
            sb.append("&headline=");
            sb.append(query.headline());
        }
        if (query.error() != null) {
            sb.append("&error-comparator=");
            sb.append(query.errorComparator());
            sb.append("&error=");
            sb.append(query.error());
        }
        if (query.user() != null) {
            sb.append("&user-comparator=");
            sb.append(query.userComparator());
            sb.append("&user=");
            sb.append(query.user());
        }
        if (query.attributeName() != null) {
            sb.append("&attribute-name=");
            sb.append(query.attributeName());
            if (query.attributeValue() != null) {
                sb.append("&attribute-value-comparator=");
                sb.append(query.attributeValueComparator());
                sb.append("&attribute-value=");
                sb.append(query.attributeValue());
            }
        }
        sb.append("&error-only=");
        sb.append(query.errorOnly());
        sb.append("&limit=");
        sb.append(query.limit());
        String content = httpClient.get("/backend/trace/points?" + sb.toString());
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        List<Trace.Header> traces = Lists.newArrayList();
        for (RawPoint point : response.getNormalPoints()) {
            traces.add(getHeader(point.getId()));
        }
        for (RawPoint point : response.getErrorPoints()) {
            traces.add(getHeader(point.getId()));
        }
        for (RawPoint point : response.getActivePoints()) {
            traces.add(getHeader(point.getId()));
        }
        return traces;
    }

    public List<Trace.Entry> getEntries(String traceId) throws Exception {
        String content = httpClient.get("/backend/trace/entries?server-id=0&trace-id=" + traceId);
        return mapper.readValue(content, new TypeReference<List<Trace.Entry>>() {});
    }

    public ProfileTree getProfile(String traceId) throws Exception {
        String content = httpClient.get("/backend/trace/profile?server-id=0&trace-id=" + traceId);
        return mapper.readValue(content, ImmutableProfileTree.class);
    }

    public void assertNoActiveTransactions() throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        // if interruptAppUnderTest() was used to terminate an active transaction, it may take a few
        // milliseconds to interrupt the thread and end the active transaction
        while (stopwatch.elapsed(SECONDS) < 2) {
            int numActiveTransactions = Integer
                    .parseInt(httpClient.get("/backend/admin/num-active-transactions?server-id=0"));
            if (numActiveTransactions == 0) {
                return;
            }
        }
        throw new AssertionError("There are still active transactions");
    }

    private @Nullable Trace.Header getActiveHeader() throws Exception {
        String content = httpClient.get("/backend/trace/points?server-id=0&from=0&to="
                + Long.MAX_VALUE + "&response-time-millis-low=0&limit=1000");
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        if (response.getActivePoints().isEmpty()) {
            return null;
        } else if (response.getActivePoints().size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            RawPoint point = response.getActivePoints().get(0);
            return getHeader(point.getId());
        }
    }

    private Trace.Header getHeader(String traceId) throws Exception {
        String content = httpClient.get("/backend/trace/header?server-id=0&trace-id=" + traceId);
        return mapper.readValue(content, ImmutableHeader.class);
    }
}
