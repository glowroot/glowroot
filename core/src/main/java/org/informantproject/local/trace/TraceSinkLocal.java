/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.trace;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.informantproject.api.JsonCharSequence;
import org.informantproject.api.LargeStringBuilder;
import org.informantproject.api.Optional;
import org.informantproject.api.SpanContextMap;
import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.ImmutableCoreConfiguration;
import org.informantproject.core.stack.MergedStackTreeNode;
import org.informantproject.core.trace.SpanImpl;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceMetricImpl;
import org.informantproject.core.trace.TraceMetricImpl.Snapshot;
import org.informantproject.core.trace.TraceSink;
import org.informantproject.core.util.DaemonExecutors;
import org.informantproject.core.util.OptionalJsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Longs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of TraceSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSinkLocal implements TraceSink {

    private static final Logger logger = LoggerFactory.getLogger(TraceSinkLocal.class);

    private static final Pattern metricMarkerMethodPattern = Pattern.compile(
            "^.*\\$informant\\$metric\\$(.*)\\$[0-9]+$");

    private final ExecutorService executorService = DaemonExecutors
            .newSingleThreadExecutor("Informant-StackCollector");

    private final ConfigurationService configurationService;
    private final TraceDao traceDao;
    private final StackTraceDao stackTraceDao;
    private final Ticker ticker;

    private final AtomicInteger queueLength = new AtomicInteger(0);

    @Inject
    public TraceSinkLocal(ConfigurationService configurationService, TraceDao traceDao,
            StackTraceDao stackTraceDao, Ticker ticker) {

        this.configurationService = configurationService;
        this.traceDao = traceDao;
        this.stackTraceDao = stackTraceDao;
        this.ticker = ticker;
    }

    public void onCompletedTrace(final Trace trace) {
        ImmutableCoreConfiguration configuration = configurationService.getCoreConfiguration();
        int thresholdMillis = configuration.getThresholdMillis();
        boolean thresholdDisabled =
                (thresholdMillis == ImmutableCoreConfiguration.THRESHOLD_DISABLED);
        long durationInNanoseconds = trace.getRootSpan().getDuration();
        // if the completed trace exceeded the given threshold then it is sent to the sink. the
        // completed trace is also checked in case it was previously sent to the sink and marked as
        // stuck, and the threshold was disabled or increased in the meantime, in which case the
        // full completed trace needs to be (re-)sent to the sink
        if ((!thresholdDisabled && durationInNanoseconds >= TimeUnit.MILLISECONDS
                .toNanos(thresholdMillis)) || trace.isStuck()) {

            queueLength.incrementAndGet();
            executorService.execute(new Runnable() {
                public void run() {
                    traceDao.storeTrace(buildStoredTrace(trace));
                    queueLength.decrementAndGet();
                }
            });
        }
    }

    public void onStuckTrace(Trace trace) {
        traceDao.storeTrace(buildStoredTrace(trace));
    }

    public int getQueueLength() {
        return queueLength.get();
    }

    public StoredTrace buildStoredTrace(Trace trace) {
        long captureTick = ticker.read();
        StoredTrace storedTrace = new StoredTrace();
        storedTrace.setId(trace.getId());
        storedTrace.setStartAt(trace.getStartDate().getTime());
        storedTrace.setStuck(trace.isStuck() && !trace.isCompleted());
        // timings for traces that are still active are normalized to the capture tick in order to
        // *attempt* to present a picture of the trace at that exact tick
        // (without using synchronization to block updates to the trace while it is being read)
        long endTick = trace.getEndTick();
        if (endTick != 0 && endTick <= captureTick) {
            storedTrace.setDuration(trace.getDuration());
            storedTrace.setCompleted(true);
        } else {
            storedTrace.setDuration(captureTick - trace.getStartTick());
            storedTrace.setCompleted(false);
        }
        SpanImpl rootSpan = trace.getRootSpan().getSpans().iterator().next();
        storedTrace.setDescription(rootSpan.getDescription().toString());
        Optional<String> username = trace.getUsername();
        if (username.isPresent()) {
            storedTrace.setUsername(username.get());
        }
        // OptionalJsonSerializer is needed for serializing trace attributes and span context maps
        Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(Optional.class,
                new OptionalJsonSerializer()).create();
        storedTrace.setAttributes(gson.toJson(trace.getAttributes()));
        storedTrace.setMetrics(getMetricsJson(trace, gson));
        Map<String, String> stackTraces = new HashMap<String, String>();
        storedTrace.setSpans(getSpansJson(trace, stackTraces, captureTick, gson));
        if (!stackTraces.isEmpty()) {
            stackTraceDao.storeStackTraces(stackTraces);
        }
        storedTrace.setMergedStackTree(getMergedStackTreeJson(trace));
        return storedTrace;
    }

    public void shutdown() {
        logger.debug("shutdown()");
        executorService.shutdownNow();
    }

    public static String getMetricsJson(Trace trace, Gson gson) {
        Collection<Snapshot> items = Collections2.transform(trace.getTraceMetrics(),
                new Function<TraceMetricImpl, Snapshot>() {
                    public Snapshot apply(TraceMetricImpl item) {
                        return item.copyOf();
                    }
                });
        if (items.isEmpty()) {
            return null;
        } else {
            Ordering<Snapshot> byTotalOrdering = new Ordering<Snapshot>() {
                @Override
                public int compare(Snapshot left, Snapshot right) {
                    return Longs.compare(left.getTotal(), right.getTotal());
                }
            };
            return gson.toJson(byTotalOrdering.reverse().sortedCopy(items));
        }
    }

    public static String getAttributesJson(Trace trace, Gson gson) {
        SpanImpl rootSpan = trace.getRootSpan().getSpans().iterator().next();
        // Span.getContextMap() may be creating the context map on the fly, so don't call it twice
        SpanContextMap contextMap = rootSpan.getContextMap();
        if (contextMap == null) {
            return null;
        } else {
            return gson.toJson(contextMap, new TypeToken<Map<String, Object>>() {}.getType());
        }
    }

    public static CharSequence getSpansJson(Trace trace, Map<String, String> stackTraces,
            long captureTick, Gson gson) {

        try {
            LargeStringBuilder sb = new LargeStringBuilder();
            JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
            jw.beginArray();
            for (SpanImpl span : trace.getRootSpan().getSpans()) {
                writeSpan(span, stackTraces, captureTick, gson, jw, sb);
            }
            jw.endArray();
            jw.close();
            return sb.build();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    public static CharSequence getMergedStackTreeJson(Trace trace) {
        if (trace.getMergedStackTree().getRootNode() == null) {
            return null;
        }
        try {
            MergedStackTreeNode rootNode = trace.getMergedStackTree().getRootNode();
            LargeStringBuilder sb = new LargeStringBuilder();
            JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
            LinkedList<Object> toVisit = new LinkedList<Object>();
            toVisit.add(rootNode);
            visitDepthFirst(toVisit, jw);
            jw.close();
            return sb.build();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    // timings for traces that are still active are normalized to the capture tick in order to
    // *attempt* to present a picture of the trace at that exact tick
    // (without using synchronization to block updates to the trace while it is being read)
    private static void writeSpan(SpanImpl span, Map<String, String> stackTraces, long captureTick,
            Gson gson, JsonWriter jw, Appendable sb) throws IOException {

        if (span.getStartTick() > captureTick) {
            // this span started after the capture tick
            return;
        }
        jw.beginObject();
        jw.name("offset");
        jw.value(span.getOffset());
        jw.name("duration");
        long endTick = span.getEndTick();
        if (endTick != 0 && endTick <= captureTick) {
            jw.value(span.getEndTick() - span.getStartTick());
        } else {
            jw.value(captureTick - span.getStartTick());
            jw.name("active");
            jw.value(true);
        }
        jw.name("index");
        jw.value(span.getIndex());
        jw.name("parentIndex");
        jw.value(span.getParentIndex());
        jw.name("level");
        jw.value(span.getLevel());
        // inject raw json into stream
        sb.append(",\"description\":\"");
        sb.append(JsonCharSequence.escapeJson(span.getDescription()));
        sb.append("\"");
        // Span.getContextMap() may be creating the context map on the fly, so don't call it twice
        SpanContextMap contextMap = span.getContextMap();
        if (contextMap != null) {
            sb.append(",\"contextMap\":");
            sb.append(gson.toJson(contextMap));
        }
        if (span.getStackTraceElements() != null) {
            String stackTraceJson = getStackTraceJson(span.getStackTraceElements());
            String stackTraceHash = Hashing.sha1().hashString(stackTraceJson, Charsets.UTF_8)
                    .toString();
            stackTraces.put(stackTraceHash, stackTraceJson);
            jw.name("stackTraceHash");
            jw.value(stackTraceHash);
        }
        jw.endObject();
    }

    private static String getStackTraceJson(StackTraceElement[] stackTraceElements)
            throws IOException {

        LargeStringBuilder sb = new LargeStringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        for (StackTraceElement stackTraceElement : stackTraceElements) {
            jw.value(stackTraceElement.toString());
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }

    private static void visitDepthFirst(LinkedList<Object> toVisit, JsonWriter jw)
            throws IOException {

        List<String> metricNameStack = Lists.newArrayList();
        while (!toVisit.isEmpty()) {
            Object curr = toVisit.removeLast();
            if (curr instanceof MergedStackTreeNode) {
                MergedStackTreeNode currNode = (MergedStackTreeNode) curr;
                jw.beginObject();
                toVisit.add(JsonWriterOp.END_OBJECT);
                if (currNode.isSyntheticRoot()) {
                    jw.name("stackTraceElement").value("<multiple root nodes>");
                } else {
                    jw.name("stackTraceElement").value(currNode.getStackTraceElement().toString());
                }
                String newMetricName = getMetricName(currNode.getStackTraceElement());
                if (newMetricName != null) {
                    // filter out successive duplicates which are common from weaving groups of
                    // overloaded methods
                    if (!newMetricName.equals(top(metricNameStack))) {
                        metricNameStack.add(newMetricName);
                        toVisit.add(JsonWriterOp.POP_METRIC_NAME);
                    }
                }
                jw.name("sampleCount").value(currNode.getSampleCount());
                if (currNode.isLeaf()) {
                    jw.name("leafThreadState").value(currNode.getLeafThreadState().name());
                    jw.name("metricNames");
                    jw.beginArray();
                    for (String metricName : metricNameStack) {
                        jw.value(metricName);
                    }
                    jw.endArray();
                }
                List<MergedStackTreeNode> childNodes = Lists.newArrayList(currNode.getChildNodes());
                if (!childNodes.isEmpty()) {
                    jw.name("childNodes").beginArray();
                    toVisit.add(JsonWriterOp.END_ARRAY);
                    toVisit.addAll(Lists.reverse(childNodes));
                }
            } else if (curr == JsonWriterOp.END_ARRAY) {
                jw.endArray();
            } else if (curr == JsonWriterOp.END_OBJECT) {
                jw.endObject();
            } else if (curr == JsonWriterOp.POP_METRIC_NAME) {
                metricNameStack.remove(metricNameStack.size() - 1);
            }
        }
    }

    private static String top(List<String> stack) {
        if (stack.isEmpty()) {
            return null;
        } else {
            return stack.get(stack.size() - 1);
        }
    }

    private static String getMetricName(StackTraceElement stackTraceElement) {
        return getMetricNameFromMethodName(stackTraceElement);
    }

    private static String getMetricNameFromMethodName(StackTraceElement stackTraceElement) {
        Matcher matcher = metricMarkerMethodPattern.matcher(stackTraceElement.getMethodName());
        if (matcher.matches()) {
            return matcher.group(1).replace("$", " ");
        } else {
            return null;
        }
    }

    private static enum JsonWriterOp {
        END_OBJECT, END_ARRAY, POP_METRIC_NAME;
    }
}
