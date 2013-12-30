/*
 * Copyright 2011-2013 the original author or authors.
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
package org.glowroot.trace;

import java.util.List;
import java.util.concurrent.TimeUnit;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Joiner;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.CompletedSpan;
import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.MetricTimer;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.Span;
import org.glowroot.common.Clock;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GeneralConfig;
import org.glowroot.config.PluginConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.config.PluginDescriptorCache;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.markers.NotThreadSafe;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.trace.model.Metric;
import org.glowroot.trace.model.MetricNameImpl;
import org.glowroot.trace.model.Trace;

/**
 * Implementation of PluginServices from the Plugin API. Each plugin gets its own instance so that
 * isEnabled(), getStringProperty(), etc can be scoped to the given plugin. The pluginId should be
 * "groupId:artifactId", using the groupId and artifactId specified in the plugin's
 * org.glowroot.plugin.json file.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class PluginServicesImpl extends PluginServices implements ConfigListener {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TraceRegistry traceRegistry;
    private final TraceCollector traceCollector;
    private final ConfigService configService;
    private final MetricNameCache metricNameCache;
    @Nullable
    private final ThreadAllocatedBytes threadAllocatedBytes;
    private final FineProfileScheduler fineProfileScheduler;
    private final Clock clock;
    private final Ticker ticker;

    // pluginId is either the id of a registered plugin or it is null
    // (see validation in constructor)
    @Nullable
    private final String pluginId;

    // cache for fast read access
    private volatile boolean enabled;
    private volatile int maxSpans;
    @Nullable
    private volatile PluginConfig pluginConfig;

    static PluginServicesImpl create(TraceRegistry traceRegistry, TraceCollector traceCollector,
            ConfigService configService, MetricNameCache metricNameCache,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            FineProfileScheduler fineProfileScheduler, Ticker ticker, Clock clock,
            PluginDescriptorCache pluginDescriptorCache, @Nullable String pluginId) {
        PluginServicesImpl pluginServices = new PluginServicesImpl(traceRegistry, traceCollector,
                configService, metricNameCache, threadAllocatedBytes, fineProfileScheduler, ticker,
                clock, pluginDescriptorCache, pluginId);
        // add config listeners first before caching configuration property values to avoid a
        // (remotely) possible race condition
        configService.addConfigListener(pluginServices);
        if (pluginId != null) {
            configService.addPluginConfigListener(pluginId, pluginServices);
        }
        // call onChange() to initialize the cached configuration property values
        pluginServices.onChange();
        return pluginServices;
    }

    PluginServicesImpl(TraceRegistry traceRegistry, TraceCollector traceCollector,
            ConfigService configService, MetricNameCache metricNameCache,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            FineProfileScheduler fineProfileScheduler, Ticker ticker, Clock clock,
            PluginDescriptorCache pluginDescriptorCache, @Nullable String pluginId) {
        this.traceRegistry = traceRegistry;
        this.traceCollector = traceCollector;
        this.configService = configService;
        this.metricNameCache = metricNameCache;
        this.threadAllocatedBytes = threadAllocatedBytes;
        this.fineProfileScheduler = fineProfileScheduler;
        this.clock = clock;
        this.ticker = ticker;
        if (pluginId == null) {
            this.pluginId = null;
        } else {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
            if (pluginConfig == null) {
                List<String> ids = Lists.newArrayList();
                List<PluginDescriptor> pluginDescriptors =
                        pluginDescriptorCache.getPluginDescriptors();
                for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
                    ids.add(pluginDescriptor.getId());
                }
                logger.warn("unexpected plugin id: {} (available plugin ids are {})", pluginId,
                        Joiner.on(", ").join(ids));
                this.pluginId = null;
            } else {
                this.pluginId = pluginId;
            }
        }
    }

    @Override
    public MetricName getMetricName(Class<?> adviceClass) {
        if (adviceClass == null) {
            logger.error("getMetricName(): argument 'adviceClass' must be non-null");
            return metricNameCache.getUnknownMetricName();
        }
        return metricNameCache.getMetricName(adviceClass);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getStringProperty(String name) {
        if (name == null) {
            logger.error("getStringProperty(): argument 'name' must be non-null");
            return "";
        }
        if (pluginConfig == null) {
            return "";
        } else {
            return pluginConfig.getStringProperty(name);
        }
    }

    @Override
    public boolean getBooleanProperty(String name) {
        if (name == null) {
            logger.error("getBooleanProperty(): argument 'name' must be non-null");
            return false;
        }
        return pluginConfig != null && pluginConfig.getBooleanProperty(name);
    }

    @Override
    @Nullable
    public Double getDoubleProperty(String name) {
        if (name == null) {
            logger.error("getDoubleProperty(): argument 'name' must be non-null");
            return null;
        }
        if (pluginConfig == null) {
            return null;
        } else {
            return pluginConfig.getDoubleProperty(name);
        }
    }

    @Override
    public void registerConfigListener(ConfigListener listener) {
        if (pluginId == null) {
            return;
        }
        if (listener == null) {
            logger.error("registerConfigListener(): argument 'listener' must be non-null");
            return;
        }
        configService.addPluginConfigListener(pluginId, listener);
    }

    @Override
    public Span startTrace(String grouping, MessageSupplier messageSupplier,
            MetricName metricName) {
        return startTrace(grouping, messageSupplier, metricName, false);
    }

    @Override
    public Span startBackgroundTrace(String grouping, MessageSupplier messageSupplier,
            MetricName metricName) {
        return startTrace(grouping, messageSupplier, metricName, true);
    }

    private Span startTrace(String grouping, MessageSupplier messageSupplier,
            MetricName metricName, boolean background) {
        if (messageSupplier == null) {
            logger.error("startTrace(): argument 'messageSupplier' must be non-null");
            return NopSpan.INSTANCE;
        }
        if (metricName == null) {
            logger.error("startTrace(): argument 'metricName' must be non-null");
            return NopSpan.INSTANCE;
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            trace = new Trace(clock.currentTimeMillis(), background, grouping, messageSupplier,
                    (MetricNameImpl) metricName, threadAllocatedBytes, ticker);
            traceRegistry.addTrace(trace);
            fineProfileScheduler.maybeScheduleFineProfilingUsingPercentage(trace);
            return new SpanImpl(trace.getRootSpan(), trace);
        } else {
            return startSpan(trace, (MetricNameImpl) metricName, messageSupplier);
        }
    }

    @Override
    public Span startSpan(MessageSupplier messageSupplier, MetricName metricName) {
        if (messageSupplier == null) {
            logger.error("startSpan(): argument 'messageSupplier' must be non-null");
            return NopSpan.INSTANCE;
        }
        if (metricName == null) {
            logger.error("startSpan(): argument 'metricName' must be non-null");
            return NopSpan.INSTANCE;
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            return NopSpan.INSTANCE;
        } else {
            return startSpan(trace, (MetricNameImpl) metricName, messageSupplier);
        }
    }

    @Override
    public MetricTimer startMetricTimer(MetricName metricName) {
        if (metricName == null) {
            logger.error("startTimer(): argument 'metricName' must be non-null");
            return NopMetricTimer.INSTANCE;
        }
        // don't call MetricImpl.start() in case this method returns NopTimer.INSTANCE below
        Metric metric = ((MetricNameImpl) metricName).get();
        if (metric == null) {
            // don't access trace thread local unless necessary
            Trace trace = traceRegistry.getCurrentTrace();
            if (trace == null) {
                return NopMetricTimer.INSTANCE;
            }
            metric = trace.addMetric((MetricNameImpl) metricName);
        }
        metric.start();
        return metric;
    }

    @Override
    public CompletedSpan addSpan(MessageSupplier messageSupplier) {
        if (messageSupplier == null) {
            logger.error("addSpan(): argument 'messageSupplier' must be non-null");
            return NopCompletedSpan.INSTANCE;
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null && trace.getSpanCount() < maxSpans) {
            // the trace limit has not been exceeded
            long currTick = ticker.read();
            return new CompletedSpanImpl(trace.addSpan(currTick, currTick, messageSupplier, null,
                    false));
        }
        return NopCompletedSpan.INSTANCE;
    }

    @Override
    public CompletedSpan addErrorSpan(ErrorMessage errorMessage) {
        if (errorMessage == null) {
            logger.error("addErrorSpan(): argument 'errorMessage' must be non-null");
            return NopCompletedSpan.INSTANCE;
        }
        Trace trace = traceRegistry.getCurrentTrace();
        // use higher span limit when adding errors, but still need some kind of cap
        if (trace != null && trace.getSpanCount() < 2 * maxSpans) {
            long currTick = ticker.read();
            return new CompletedSpanImpl(
                    trace.addSpan(currTick, currTick, null, errorMessage, true));
        }
        return NopCompletedSpan.INSTANCE;
    }

    @Override
    public void setGrouping(String grouping) {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.setGrouping(grouping);
        }
    }

    @Override
    public void setUserId(@Nullable String userId) {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.setUserId(userId);
            if (userId != null && trace.getFineProfilerScheduledRunnable() == null) {
                fineProfileScheduler.maybeScheduleFineProfilingUsingUserId(trace, userId);
            }
        }
    }

    @Override
    public void setTraceAttribute(String name, @Nullable String value) {
        if (pluginId == null) {
            return;
        }
        if (name == null) {
            logger.error("setTraceAttribute(): argument 'name' must be non-null");
            return;
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.setAttribute(pluginId, name, value);
        }
    }

    public void onChange() {
        GeneralConfig generalConfig = configService.getGeneralConfig();
        if (pluginId == null) {
            enabled = generalConfig.isEnabled();
        } else {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
            if (pluginConfig == null) {
                // pluginId was already validated at construction time so this should not happen
                logger.error("plugin config not found for plugin id: {}", pluginId);
                enabled = generalConfig.isEnabled();
            } else {
                enabled = generalConfig.isEnabled() && pluginConfig.isEnabled();
            }
            this.pluginConfig = pluginConfig;
        }
        maxSpans = generalConfig.getMaxSpans();
    }

    private Span startSpan(Trace trace, MetricNameImpl metricName,
            MessageSupplier messageSupplier) {
        long startTick = ticker.read();
        if (trace.getSpanCount() >= maxSpans) {
            // the span limit has been exceeded for this trace
            trace.addSpanLimitExceededMarkerIfNeeded();
            Metric metric = metricName.get();
            if (metric == null) {
                metric = trace.addMetric(metricName);
            }
            metric.start(startTick);
            return new TimerWrappedInSpan(metric, startTick, trace, messageSupplier);
        } else {
            return new SpanImpl(trace.pushSpan(metricName, startTick, messageSupplier), trace);
        }
    }

    private static ImmutableList<StackTraceElement> captureSpanStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        // need to strip back a few stack calls:
        // skip i=0 which is "java.lang.Thread.getStackTrace()"
        for (int i = 1; i < stackTrace.length; i++) {
            // startsWith to include nested classes
            if (!stackTrace[i].getClassName().startsWith(PluginServicesImpl.class.getName())) {
                // found the caller of PluginServicesImpl, this should be the @Pointcut
                // @OnReturn/@OnThrow/@OnAfter method, next one should be the woven method
                return ImmutableList.copyOf(stackTrace).subList(i + 1, stackTrace.length);
            }
        }
        logger.warn("stack trace didn't include endWithStackTrace()");
        return ImmutableList.of();
    }

    @NotThreadSafe
    private class SpanImpl implements Span {
        private final org.glowroot.trace.model.Span span;
        private final Trace trace;
        private SpanImpl(org.glowroot.trace.model.Span span, Trace trace) {
            this.span = span;
            this.trace = trace;
        }
        public CompletedSpan end() {
            return endInternal(ticker.read(), null);
        }
        public CompletedSpan endWithStackTrace(long threshold, TimeUnit unit) {
            long endTick = ticker.read();
            if (endTick - span.getStartTick() >= unit.toNanos(threshold)) {
                span.setStackTrace(captureSpanStackTrace());
            }
            return endInternal(endTick, null);
        }
        public CompletedSpan endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.error("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                return end();
            } else {
                return endInternal(ticker.read(), errorMessage);
            }
        }
        public MessageSupplier getMessageSupplier() {
            MessageSupplier messageSupplier = span.getMessageSupplier();
            if (messageSupplier == null) {
                // this should be impossible since span.getMessageSupplier() is only null when the
                // span was created using addErrorSpan(), and that method doesn't return the span
                // afterwards, so it should be impossible to call getMessageSupplier() on it
                throw new AssertionError("Somehow got hold of an error Span??");
            }
            return messageSupplier;
        }
        private CompletedSpan endInternal(long endTick, @Nullable ErrorMessage errorMessage) {
            trace.popSpan(span, endTick, errorMessage);
            if (trace.isCompleted()) {
                // the root span has been popped off
                safeCancel(trace.getCoarseProfilerScheduledRunnable());
                safeCancel(trace.getStuckScheduledRunnable());
                safeCancel(trace.getFineProfilerScheduledRunnable());
                // send to trace collector before removing from trace registry so that trace
                // collector can cover the gap (via TraceCollectorImpl.getPendingCompleteTraces())
                // between removing the trace from the registry and storing it
                traceCollector.onCompletedTrace(trace);
                traceRegistry.removeTrace(trace);
                trace.clearThreadLocalMetrics();
            }
            return new CompletedSpanImpl(span);
        }
        private void safeCancel(@Nullable ScheduledRunnable scheduledRunnable) {
            if (scheduledRunnable == null) {
                return;
            }
            scheduledRunnable.cancel();
        }
    }

    @NotThreadSafe
    private class TimerWrappedInSpan implements Span {
        private final Metric metric;
        private final long startTick;
        private final Trace trace;
        private final MessageSupplier messageSupplier;
        public TimerWrappedInSpan(Metric metric, long startTick, Trace trace,
                MessageSupplier messageSupplier) {
            this.metric = metric;
            this.startTick = startTick;
            this.trace = trace;
            this.messageSupplier = messageSupplier;
        }
        public CompletedSpan end() {
            metric.stop();
            return NopCompletedSpan.INSTANCE;
        }
        public CompletedSpan endWithStackTrace(long threshold, TimeUnit unit) {
            long endTick = ticker.read();
            metric.end(endTick);
            // use higher span limit when adding slow spans, but still need some kind of cap
            if (endTick - startTick >= unit.toNanos(threshold)
                    && trace.getSpanCount() < 2 * maxSpans) {
                // span won't necessarily be nested properly, and won't have any timing data, but at
                // least the long span and stack trace will get captured
                org.glowroot.trace.model.Span span =
                        trace.addSpan(startTick, endTick, messageSupplier, null, true);
                span.setStackTrace(captureSpanStackTrace());
                return new CompletedSpanImpl(span);
            }
            return NopCompletedSpan.INSTANCE;
        }
        public CompletedSpan endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.error("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                return end();
            }
            long endTick = ticker.read();
            metric.end(endTick);
            // use higher span limit when adding errors, but still need some kind of cap
            if (trace.getSpanCount() < 2 * maxSpans) {
                // span won't be nested properly, but at least the error will get captured
                return new CompletedSpanImpl(trace.addSpan(startTick, endTick, messageSupplier,
                        errorMessage, true));
            }
            return NopCompletedSpan.INSTANCE;
        }
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
        }
    }

    private static class CompletedSpanImpl implements CompletedSpan {
        private final org.glowroot.trace.model.Span span;
        private CompletedSpanImpl(org.glowroot.trace.model.Span span) {
            this.span = span;
        }
        public void captureSpanStackTrace() {
            span.setStackTrace(PluginServicesImpl.captureSpanStackTrace());
        }
    }

    private static class NopSpan implements Span {
        private static final NopSpan INSTANCE = new NopSpan();
        private NopSpan() {}
        public CompletedSpan end() {
            return NopCompletedSpan.INSTANCE;
        }
        public CompletedSpan endWithStackTrace(long threshold, TimeUnit unit) {
            return NopCompletedSpan.INSTANCE;
        }
        public CompletedSpan endWithError(ErrorMessage errorMessage) {
            return NopCompletedSpan.INSTANCE;
        }
        @Nullable
        public MessageSupplier getMessageSupplier() {
            return null;
        }
    }

    @ThreadSafe
    private static class NopMetricTimer implements MetricTimer {
        private static final NopMetricTimer INSTANCE = new NopMetricTimer();
        public void stop() {}
    }

    private static class NopCompletedSpan implements CompletedSpan {
        private static final NopCompletedSpan INSTANCE = new NopCompletedSpan();
        public void captureSpanStackTrace() {}
    }
}
