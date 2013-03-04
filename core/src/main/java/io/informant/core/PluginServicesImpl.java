/**
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
package io.informant.core;

import io.informant.api.ErrorMessage;
import io.informant.api.MessageSupplier;
import io.informant.api.Metric;
import io.informant.api.MetricTimer;
import io.informant.api.PluginServices;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.api.Span;
import io.informant.config.ConfigService;
import io.informant.config.FineProfilingConfig;
import io.informant.config.GeneralConfig;
import io.informant.config.PluginConfig;
import io.informant.config.PluginDescriptor;
import io.informant.config.PluginDescriptorCache;
import io.informant.config.UserConfig;
import io.informant.util.Clock;
import io.informant.util.NotThreadSafe;
import io.informant.util.ThreadSafe;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Implementation of PluginServices from the Plugin API. Each plugin gets its own instance so that
 * isEnabled(), getStringProperty(), etc can be scoped to the given plugin. The pluginId should be
 * "groupId:artifactId", constructed from the plugin's maven coordinates (or at least matching the
 * groupId and artifactId specified in the plugin's io.informant.plugin.json).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class PluginServicesImpl extends PluginServices implements ConfigListener {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TraceRegistry traceRegistry;
    private final TraceSink traceSink;
    private final ConfigService configService;
    private final MetricCache metricCache;
    private final FineGrainedProfiler fineGrainedProfiler;
    private final Clock clock;
    private final Ticker ticker;
    private final Random random;
    private final WeavingMetricImpl weavingMetric;

    // pluginId should be "groupId:artifactId", based on the groupId and artifactId specified in the
    // plugin's io.informant.plugin.json
    private final String pluginId;

    // cache for fast read access
    private volatile boolean enabled;
    private volatile int maxSpans;
    @Nullable
    private volatile PluginConfig pluginConfig;

    PluginServicesImpl(TraceRegistry traceRegistry, TraceSink traceSink,
            ConfigService configService, MetricCache metricCache,
            FineGrainedProfiler fineGrainedProfiler, Ticker ticker, Clock clock, Random random,
            WeavingMetricImpl weavingMetric, PluginDescriptorCache pluginDescriptorCache,
            String pluginId) {
        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.configService = configService;
        this.metricCache = metricCache;
        this.fineGrainedProfiler = fineGrainedProfiler;
        this.clock = clock;
        this.ticker = ticker;
        this.random = random;
        this.weavingMetric = weavingMetric;
        this.pluginId = pluginId;
        // add config listener first before caching config properties to avoid a
        // (remotely) possible race condition
        configService.addConfigListener(this);
        configService.addPluginConfigListener(pluginId, this);
        pluginConfig = configService.getPluginConfig(pluginId);
        if (pluginConfig == null) {
            List<String> ids = Lists.newArrayList();
            for (PluginDescriptor pluginDescriptor : pluginDescriptorCache.getPluginDescriptors()) {
                ids.add(pluginDescriptor.getId());
            }
            logger.warn("unexpected plugin id '{}', available plugin ids: {}", pluginId,
                    Joiner.on(", ").join(ids));
        }
        // call onChange() to initialize the cached configuration property values
        onChange();
    }

    @Override
    public Metric getMetric(Class<?> adviceClass) {
        return metricCache.getMetric(adviceClass);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getStringProperty(String name) {
        if (name == null) {
            logger.warn("getStringProperty(): argument 'name' must be non-null");
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
            logger.warn("getBooleanProperty(): argument 'name' must be non-null");
            return false;
        }
        if (pluginConfig == null) {
            return false;
        } else {
            return pluginConfig.getBooleanProperty(name);
        }
    }

    @Override
    @Nullable
    public Double getDoubleProperty(String name) {
        if (name == null) {
            logger.warn("getDoubleProperty(): argument 'name' must be non-null");
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
        if (listener == null) {
            logger.warn("registerConfigListener(): argument 'listener' must be non-null");
            return;
        }
        configService.addPluginConfigListener(pluginId, listener);
    }

    @Override
    public Span startTrace(MessageSupplier messageSupplier, Metric metric) {
        return startTrace(messageSupplier, metric, false);
    }

    @Override
    public Span startBackgroundTrace(MessageSupplier messageSupplier, Metric metric) {
        return startTrace(messageSupplier, metric, true);
    }

    private Span startTrace(MessageSupplier messageSupplier, Metric metric, boolean background) {
        if (messageSupplier == null) {
            logger.warn("startTrace(): argument 'messageSupplier' must be non-null");
            return new NopSpan(messageSupplier);
        }
        if (metric == null) {
            logger.warn("startTrace(): argument 'metric' must be non-null");
            return new NopSpan(messageSupplier);
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            trace = new Trace((MetricImpl) metric, messageSupplier, ticker, clock, weavingMetric);
            trace.setBackground(background);
            traceRegistry.addTrace(trace);
            maybeScheduleFineProfilingUsingPercentage(trace);
            return new SpanImpl(trace.getRootSpan(), trace);
        } else {
            return startSpan(trace, (MetricImpl) metric, messageSupplier);
        }
    }

    @Override
    public Span startSpan(MessageSupplier messageSupplier, Metric metric) {
        if (messageSupplier == null) {
            logger.warn("startSpan(): argument 'messageSupplier' must be non-null");
            return new NopSpan(messageSupplier);
        }
        if (metric == null) {
            logger.warn("startSpan(): argument 'metric' must be non-null");
            return new NopSpan(messageSupplier);
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            return new NopSpan(messageSupplier);
        } else {
            return startSpan(trace, (MetricImpl) metric, messageSupplier);
        }
    }

    @Override
    public MetricTimer startMetricTimer(Metric metric) {
        if (metric == null) {
            logger.warn("startTimer(): argument 'metric' must be non-null");
            return NopMetricTimer.INSTANCE;
        }
        // don't call MetricImpl.start() in case this method returns NopTimer.INSTANCE below
        TraceMetric traceMetric = ((MetricImpl) metric).get();
        if (!traceMetric.isLinkedToTrace()) {
            // don't access trace thread local unless necessary
            Trace trace = traceRegistry.getCurrentTrace();
            if (trace == null) {
                return NopMetricTimer.INSTANCE;
            }
            trace.linkTraceMetric((MetricImpl) metric, traceMetric);
        }
        traceMetric.start();
        return traceMetric;
    }

    @Override
    public void addSpan(MessageSupplier messageSupplier) {
        if (messageSupplier == null) {
            logger.warn("addSpan(): argument 'messageSupplier' must be non-null");
            return;
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null && trace.getSpanCount() < maxSpans) {
            // the trace limit has not been exceeded
            trace.addSpan(messageSupplier, null, false);
        }
    }

    @Override
    public void addErrorSpan(ErrorMessage errorMessage) {
        if (errorMessage == null) {
            logger.warn("addErrorSpan(): argument 'errorMessage' must be non-null");
            return;
        }
        Trace trace = traceRegistry.getCurrentTrace();
        // use higher span limit when adding errors, but still need some kind of cap
        if (trace != null && trace.getSpanCount() < 2 * maxSpans) {
            trace.addSpan(null, errorMessage, true);
        }
    }

    @Override
    public void setUserId(@Nullable String userId) {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.setUserId(userId);
            if (userId != null && trace.getFineProfilingScheduledFuture() == null) {
                maybeScheduleFineProfilingUsingUserId(trace, userId);
            }
        }
    }

    @Override
    public void setTraceAttribute(String name, @Nullable String value) {
        if (name == null) {
            logger.warn("setTraceAttribute(): argument 'name' must be non-null");
            return;
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.setAttribute(pluginId, name, value);
        }
    }

    public void onChange() {
        GeneralConfig generalConfig = configService.getGeneralConfig();
        pluginConfig = configService.getPluginConfig(pluginId);
        enabled = generalConfig.isEnabled() && pluginConfig != null && pluginConfig.isEnabled();
        maxSpans = generalConfig.getMaxSpans();
    }

    private void maybeScheduleFineProfilingUsingUserId(Trace trace, String userId) {
        UserConfig userConfig = configService.getUserConfig();
        if (userConfig.isEnabled() && userConfig.isFineProfiling()
                && userId.equals(userConfig.getUserId())) {
            fineGrainedProfiler.scheduleProfiling(trace);
        }
    }

    private void maybeScheduleFineProfilingUsingPercentage(Trace trace) {
        FineProfilingConfig fineProfilingConfig = configService.getFineProfilingConfig();
        if (fineProfilingConfig.isEnabled()
                && random.nextDouble() * 100 < fineProfilingConfig.getTracePercentage()) {
            fineGrainedProfiler.scheduleProfiling(trace);
        }
    }

    private Span startSpan(Trace trace, MetricImpl metric, MessageSupplier messageSupplier) {
        if (trace.getSpanCount() >= maxSpans) {
            // the span limit has been exceeded for this trace
            trace.addSpanLimitExceededMarkerIfNeeded();
            long startTick = ticker.read();
            TraceMetric traceMetric = metric.start(startTick);
            if (!traceMetric.isLinkedToTrace()) {
                trace.linkTraceMetric(metric, traceMetric);
            }
            return new TimerWrappedInSpan(traceMetric, startTick, trace, messageSupplier);
        } else {
            return new SpanImpl(trace.pushSpan(metric, messageSupplier, false), trace);
        }
    }

    private static ImmutableList<StackTraceElement> captureSpanStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        // need to strip back a few stack calls:
        // 0 - java.lang.Thread.getStackTrace()
        // 1 - io.informant.core.PluginServicesImpl$SpanImpl.captureSpanStackTrace()
        // 2? - io.informant.core.PluginServicesImpl$SpanImpl.access$1()
        // 3 - io.informant.core.PluginServicesImpl$SpanImpl.endWithStackTrace()
        // 4 - the @Pointcut @OnReturn/@OnThrow/@OnAfter method
        // (possibly more if the @Pointcut method doesn't call end()/endWithError() directly)
        for (int i = 0; i < stackTrace.length; i++) {
            if (stackTrace[i].getMethodName().equals("endWithStackTrace")) {
                return ImmutableList.copyOf(stackTrace).subList(i + 2, stackTrace.length);
            }
        }
        logger.warn("stack trace didn't include endWithStackTrace()");
        return ImmutableList.of();
    }

    @NotThreadSafe
    private class SpanImpl implements Span {
        private final io.informant.core.Span span;
        private final Trace trace;
        private SpanImpl(io.informant.core.Span span, Trace trace) {
            this.span = span;
            this.trace = trace;
        }
        public void end() {
            endInternal(ticker.read(), null);
        }
        public void endWithStackTrace(long threshold, TimeUnit unit) {
            long endTick = ticker.read();
            if (endTick - span.getStartTick() >= unit.toNanos(threshold)) {
                span.setStackTrace(captureSpanStackTrace());
            }
            endInternal(endTick, null);
        }
        public void endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.warn("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                end();
            } else {
                endInternal(ticker.read(), errorMessage);
            }
        }
        public MessageSupplier getMessageSupplier() {
            MessageSupplier messageSupplier = span.getMessageSupplier();
            if (messageSupplier == null) {
                // this should be impossible since span.getMessageSupplier() is only null when the
                // span was created using addErrorSpan(), and that method doesn't return the span
                // afterwards, so it should be impossible to call getMessageSupplier() on it
                throw new NullPointerException("Somehow got hold of an error Span??");
            }
            return messageSupplier;
        }
        private void endInternal(long endTick, @Nullable ErrorMessage errorMessage) {
            trace.popSpan(span, endTick, errorMessage);
            if (trace.isCompleted()) {
                // the root span has been popped off
                cancelScheduledFuture(trace.getCoarseProfilingScheduledFuture());
                cancelScheduledFuture(trace.getStuckScheduledFuture());
                cancelScheduledFuture(trace.getFineProfilingScheduledFuture());
                // send to trace sink before removing from trace registry so that trace sink
                // can cover the gap (via TraceSink.getPendingTraces()) between removing the trace
                // from the registry and storing it
                traceSink.onCompletedTrace(trace);
                traceRegistry.removeTrace(trace);
                // if the thread local trace metrics are still needed they should have been promoted
                // by TraceSink.onCompletedTrace() above (via Trace.promoteTraceMetrics())
                trace.resetTraceMetrics();
            }
        }
        private void cancelScheduledFuture(@Nullable ScheduledFuture<?> scheduledFuture) {
            if (scheduledFuture == null) {
                return;
            }
            boolean success = scheduledFuture.cancel(false);
            if (!success) {
                // execution failed due to an error (probably programming error)
                try {
                    scheduledFuture.get();
                } catch (InterruptedException e) {
                    logger.error(e.getMessage(), e);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TerminateScheduledActionException)) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        }
    }

    @NotThreadSafe
    private class TimerWrappedInSpan implements Span {
        private final TraceMetric traceMetric;
        private final long startTick;
        private final Trace trace;
        private final MessageSupplier messageSupplier;
        public TimerWrappedInSpan(TraceMetric metricTimer, long startTick, Trace trace,
                MessageSupplier messageSupplier) {
            this.traceMetric = metricTimer;
            this.startTick = startTick;
            this.trace = trace;
            this.messageSupplier = messageSupplier;
        }
        public void end() {
            traceMetric.stop();
        }
        public void endWithStackTrace(long threshold, TimeUnit unit) {
            long endTick = ticker.read();
            traceMetric.end(endTick);
            // use higher span limit when adding slow spans, but still need some kind of cap
            if (endTick - startTick >= unit.toNanos(threshold)
                    && trace.getSpanCount() < 2 * maxSpans) {
                // span won't necessarily be nested properly, and won't have any timing data, but at
                // least the long span and stack trace will get captured
                io.informant.core.Span span = trace.addSpan(messageSupplier, null, true);
                span.setStackTrace(captureSpanStackTrace());
            }
        }
        public void endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.warn("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                end();
                return;
            }
            traceMetric.stop();
            // use higher span limit when adding errors, but still need some kind of cap
            if (trace.getSpanCount() < 2 * maxSpans) {
                // span won't necessarily be nested properly, and won't have any timing data, but at
                // least the error will get captured
                trace.addSpan(messageSupplier, errorMessage, true);
            }
        }
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
        }
    }

    private static class NopSpan implements Span {
        private final MessageSupplier messageSupplier;
        private NopSpan(MessageSupplier messageSupplier) {
            this.messageSupplier = messageSupplier;
        }
        public void end() {}
        public void endWithStackTrace(long threshold, TimeUnit unit) {}
        public void endWithError(ErrorMessage errorMessage) {}
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
        }
    }

    @ThreadSafe
    private static class NopMetricTimer implements MetricTimer {
        private static final NopMetricTimer INSTANCE = new NopMetricTimer();
        public void stop() {}
    }
}
