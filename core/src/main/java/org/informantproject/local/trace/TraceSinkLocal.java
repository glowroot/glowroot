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
import org.informantproject.core.trace.MetricDataItem;
import org.informantproject.core.trace.MetricDataItem.Snapshot;
import org.informantproject.core.trace.PluginServicesImpl;
import org.informantproject.core.trace.Span;
import org.informantproject.core.trace.Trace;
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

    private static final Pattern spanMarkerMethodPattern = Pattern.compile("(.*)SpanMarker[0-9]*");

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
        Span rootSpan = trace.getRootSpan().getSpans().iterator().next();
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
        Collection<Snapshot> items = Collections2.transform(trace.getMetricDataItems(),
                new Function<MetricDataItem, Snapshot>() {
                    public Snapshot apply(MetricDataItem item) {
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
        Span rootSpan = trace.getRootSpan().getSpans().iterator().next();
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
            for (Span span : trace.getRootSpan().getSpans()) {
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
    private static void writeSpan(Span span, Map<String, String> stackTraces, long captureTick,
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

        List<String> spanNameStack = Lists.newArrayList();
        Object previous = null;
        while (!toVisit.isEmpty()) {
            Object curr = toVisit.removeLast();
            if (curr instanceof MergedStackTreeNode) {
                MergedStackTreeNode currNode = (MergedStackTreeNode) curr;
                jw.beginObject();
                if (currNode.isSyntheticRoot()) {
                    jw.name("stackTraceElement").value("<multiple root nodes>");
                } else {
                    jw.name("stackTraceElement").value(currNode.getStackTraceElement().toString());
                }
                String newSpanName = null;
                if (previous instanceof MergedStackTreeNode) {
                    newSpanName = getSpanName(currNode.getStackTraceElement(),
                            ((MergedStackTreeNode) previous).getStackTraceElement());
                    if (newSpanName != null) {
                        spanNameStack.add(newSpanName);
                    }
                }
                jw.name("sampleCount").value(currNode.getSampleCount());
                if (currNode.isLeaf()) {
                    jw.name("leafThreadState").value(currNode.getLeafThreadState().name());
                    jw.name("spanNames");
                    jw.beginArray();
                    for (String spanName : spanNameStack) {
                        jw.value(spanName);
                    }
                    jw.endArray();
                }
                List<MergedStackTreeNode> childNodes = Lists.newArrayList(currNode.getChildNodes());
                if (childNodes.isEmpty()) {
                    jw.endObject();
                } else {
                    if (newSpanName != null) {
                        toVisit.add(JsonWriterOp.POP_SPAN_NAME);
                    }
                    toVisit.add(JsonWriterOp.END_OBJECT);
                    jw.name("childNodes").beginArray();
                    toVisit.add(JsonWriterOp.END_ARRAY);
                    toVisit.addAll(Lists.reverse(childNodes));
                }
            } else if (curr == JsonWriterOp.END_ARRAY) {
                jw.endArray();
            } else if (curr == JsonWriterOp.END_OBJECT) {
                jw.endObject();
            } else if (curr == JsonWriterOp.POP_SPAN_NAME) {
                spanNameStack.remove(spanNameStack.size() - 1);
            }
            previous = curr;
        }
    }

    private static String getSpanName(StackTraceElement curr, StackTraceElement previous) {
        if (isNewSpan(curr, previous)) {
            return getSpanNameFromMethodName(previous);
        }
        return null;
    }

    private static boolean isNewSpan(StackTraceElement curr, StackTraceElement previous) {
        if (!curr.getClassName().equals(PluginServicesImpl.class.getName())) {
            return false;
        }
        if (!curr.getMethodName().startsWith("execute") && !curr.getMethodName().startsWith(
                "proceed")) {
            // ignore other methods in PluginServicesImpl
            return false;
        }
        if (previous.getClassName().equals(PluginServicesImpl.class.getName())) {
            // ignore PluginServicesImpl calling itself
            return false;
        }
        return true;
    }

    private static String getSpanNameFromMethodName(StackTraceElement stackTraceElement) {
        Matcher matcher = spanMarkerMethodPattern.matcher(stackTraceElement.getMethodName());
        if (matcher.matches()) {
            String spanName = matcher.group(1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < spanName.length(); i++) {
                char c = spanName.charAt(i);
                if (Character.isUpperCase(c)) {
                    sb.append(" ");
                    sb.append(Character.toLowerCase(c));
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } else {
            logger.warn("Informant plugin is not following SpanMarker method"
                    + " naming convention: {}", stackTraceElement);
            return null;
        }
    }

    private static enum JsonWriterOp {
        END_OBJECT, END_ARRAY, POP_SPAN_NAME;
    }
}
