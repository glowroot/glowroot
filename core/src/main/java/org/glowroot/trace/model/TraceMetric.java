/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.trace.model;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Objects;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.glowroot.api.TraceMetricName;
import org.glowroot.markers.PartiallyThreadSafe;

/**
 * All timing data is in nanoseconds.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@PartiallyThreadSafe("writeValue() can be called from any thread")
public class TraceMetric implements TraceMetricTimerExt {

    private final TraceMetricName traceMetricName;
    // nanosecond rollover (292 years) isn't a concern for total time on a single trace
    private long total;
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;
    private long count;

    private long startTick;
    // selfNestingLevel is written after any non-volatile fields are written and it is read before
    // any non-volatile fields are read, creating a memory barrier and making the latest values of
    // the non-volatile fields visible to the reading thread
    private volatile int selfNestingLevel;

    // nestedTraceMetrics is only accessed by trace thread so no need for volatile or synchronized
    // access during metric capture which is important
    //
    // lazy initialize to save memory in common case where this is a leaf metric
    @Nullable
    private Map<TraceMetricName, TraceMetric> nestedTraceMetrics;

    // separate list for thread safe access by other threads (e.g. stuck trace capture and active
    // trace viewer)
    //
    // lazy initialize to save memory in common case where this is a leaf metric
    @Nullable
    private volatile List<TraceMetric> threadSafeNestedTraceMetrics;

    // trace and parent don't need to be thread safe as they are only accessed by the trace thread
    private final Trace trace;
    @Nullable
    private final TraceMetric parent;

    private final Ticker ticker;

    TraceMetric(TraceMetricName traceMetricName, Trace trace, @Nullable TraceMetric parent,
            Ticker ticker) {
        this.traceMetricName = traceMetricName;
        this.trace = trace;
        this.parent = parent;
        this.ticker = ticker;
    }

    // safe to be called from another thread
    public void writeValue(JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        jg.writeStringField("name", getName());

        // selfNestingLevel is read first since it is used as a memory barrier so that the
        // non-volatile fields below will be visible to this thread
        boolean active = selfNestingLevel > 0;

        if (active) {
            // try to grab a quick, consistent snapshot, but no guarantee on consistency since the
            // trace is active
            //
            // grab total before curr, to avoid case where total is updated in between
            // these two lines and then "total + curr" would overstate the correct value
            // (it seems better to understate the correct value if there is an update to the metric
            // values in between these two lines)
            long theTotal = this.total;
            // capture startTick before ticker.read() so curr is never < 0
            long theStartTick = this.startTick;
            long curr = ticker.read() - theStartTick;
            if (theTotal == 0) {
                jg.writeNumberField("total", curr);
                jg.writeNumberField("min", curr);
                jg.writeNumberField("max", curr);
                jg.writeNumberField("count", 1);
                jg.writeBooleanField("active", true);
                jg.writeBooleanField("minActive", true);
                jg.writeBooleanField("maxActive", true);
            } else {
                jg.writeNumberField("total", theTotal + curr);
                jg.writeNumberField("min", min);
                if (curr > max) {
                    jg.writeNumberField("max", curr);
                } else {
                    jg.writeNumberField("max", max);
                }
                jg.writeNumberField("count", count + 1);
                jg.writeBooleanField("active", true);
                jg.writeBooleanField("minActive", false);
                if (curr > max) {
                    jg.writeBooleanField("maxActive", true);
                } else {
                    jg.writeBooleanField("maxActive", false);
                }
            }
        } else {
            jg.writeNumberField("total", total);
            jg.writeNumberField("min", min);
            jg.writeNumberField("max", max);
            jg.writeNumberField("count", count);
            jg.writeBooleanField("active", false);
            jg.writeBooleanField("minActive", false);
            jg.writeBooleanField("maxActive", false);
        }
        if (threadSafeNestedTraceMetrics != null) {
            ImmutableList<TraceMetric> copyOfNestedTraceMetrics;
            synchronized (threadSafeNestedTraceMetrics) {
                copyOfNestedTraceMetrics = ImmutableList.copyOf(threadSafeNestedTraceMetrics);
            }
            jg.writeArrayFieldStart("nestedTraceMetrics");
            for (TraceMetric nestedMetric : copyOfNestedTraceMetrics) {
                nestedMetric.writeValue(jg);
            }
            jg.writeEndArray();
        }
        jg.writeEndObject();
    }

    @Override
    public void stop() {
        end(ticker.read());
    }

    void start(long startTick) {
        this.startTick = startTick;
        // selfNestingLevel is incremented after updating startTick since selfNestingLevel is used
        // as a memory barrier so startTick will be visible to other threads in copyOf()
        selfNestingLevel++;
    }

    @Override
    public void end(long endTick) {
        if (selfNestingLevel == 1) {
            recordData(endTick - startTick);
            trace.setActiveMetric(parent);
        }
        // selfNestingLevel is decremented after recording data since it is volatile and creates a
        // memory barrier so that all updated fields will be visible to other threads in copyOf()
        selfNestingLevel--;
    }

    public TraceMetricName getTraceMetricName() {
        return traceMetricName;
    }

    public String getName() {
        return ((TraceMetricNameImpl) traceMetricName).getName();
    }

    // only called by trace thread
    public long getTotal() {
        return total;
    }

    // only called by trace thread
    public long getCount() {
        return count;
    }

    // only called by trace thread at trace completion
    public List<TraceMetric> getNestedTraceMetrics() {
        if (threadSafeNestedTraceMetrics == null) {
            return ImmutableList.of();
        } else {
            return threadSafeNestedTraceMetrics;
        }
    }

    void incrementSelfNestingLevel() {
        selfNestingLevel++;
    }

    // only called by trace thread
    TraceMetric getNestedMetric(TraceMetricName traceMetricName, Trace trace) {
        if (nestedTraceMetrics == null) {
            nestedTraceMetrics = Maps.newHashMap();
        }
        TraceMetric nestedMetric = nestedTraceMetrics.get(traceMetricName);
        if (nestedMetric != null) {
            return nestedMetric;
        }
        nestedMetric = new TraceMetric(traceMetricName, trace, this, ticker);
        nestedTraceMetrics.put(traceMetricName, nestedMetric);
        if (threadSafeNestedTraceMetrics == null) {
            threadSafeNestedTraceMetrics = Lists.newArrayList();
        }
        synchronized (threadSafeNestedTraceMetrics) {
            threadSafeNestedTraceMetrics.add(nestedMetric);
        }
        return nestedMetric;
    }

    private void recordData(long time) {
        if (time > max) {
            max = time;
        }
        if (time < min) {
            min = time;
        }
        count++;
        total += time;
    }

    /*@Pure*/
    @Override
    public String toString() {
        ImmutableList<TraceMetric> copyOfNestedTraceMetrics = null;
        if (threadSafeNestedTraceMetrics != null) {
            synchronized (threadSafeNestedTraceMetrics) {
                copyOfNestedTraceMetrics = ImmutableList.copyOf(threadSafeNestedTraceMetrics);
            }
        }
        return Objects.toStringHelper(this)
                .add("name", getName())
                .add("total", total)
                .add("min", min)
                .add("max", max)
                .add("count", count)
                .add("startTick", startTick)
                .add("selfNestingLevel", selfNestingLevel)
                .add("nestedTraceMetrics", copyOfNestedTraceMetrics)
                .toString();
    }
}
