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
package io.informant.trace;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Joiner;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.CompletedSpan;
import io.informant.api.ErrorMessage;
import io.informant.api.MessageSupplier;
import io.informant.api.MetricName;
import io.informant.api.MetricTimer;
import io.informant.api.PluginServices;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.api.Span;
import io.informant.common.Clock;
import io.informant.config.ConfigService;
import io.informant.config.GeneralConfig;
import io.informant.config.PluginConfig;
import io.informant.config.PluginDescriptor;
import io.informant.config.PluginDescriptorCache;
import io.informant.markers.NotThreadSafe;
import io.informant.markers.ThreadSafe;
import io.informant.trace.CollectStackCommand.TerminateScheduledActionException;
import io.informant.trace.model.Metric;
import io.informant.trace.model.MetricNameImpl;
import io.informant.trace.model.Trace;
import io.informant.trace.model.WeavingMetricNameImpl;

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

    private static final String DYNAMIC_POINTCUTS_PLUGIN_ID = "io.informant:dynamic-pointcuts";

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TraceRegistry traceRegistry;
    private final TraceSink traceSink;
    private final ConfigService configService;
    private final MetricCache metricCache;
    private final FineProfileScheduler fineProfileScheduler;
    private final Clock clock;
    private final Ticker ticker;
    private final WeavingMetricNameImpl weavingMetricName;

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
            FineProfileScheduler fineProfileScheduler, Ticker ticker, Clock clock,
            WeavingMetricNameImpl weavingMetricName, PluginDescriptorCache pluginDescriptorCache,
            String pluginId) {
        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.configService = configService;
        this.metricCache = metricCache;
        this.fineProfileScheduler = fineProfileScheduler;
        this.clock = clock;
        this.ticker = ticker;
        this.weavingMetricName = weavingMetricName;
        this.pluginId = pluginId;
        // add config listener first before caching config properties to avoid a
        // (remotely) possible race condition
        configService.addConfigListener(this);
        configService.addPluginConfigListener(pluginId, this);
        if (!pluginId.equals(DYNAMIC_POINTCUTS_PLUGIN_ID)) {
            pluginConfig = configService.getPluginConfig(pluginId);
            if (pluginConfig == null) {
                List<String> ids = Lists.newArrayList();
                for (PluginDescriptor pluginDescriptor : pluginDescriptorCache
                        .getPluginDescriptors()) {
                    ids.add(pluginDescriptor.getId());
                }
                logger.warn("unexpected plugin id '{}', available plugin ids: {}", pluginId,
                        Joiner.on(", ").join(ids));
            }
        }
        // call onChange() to initialize the cached configuration property values
        onChange();
    }

    @Override
    public MetricName getMetricName(Class<?> adviceClass) {
        if (adviceClass == null) {
            logger.error("getMetricName(): argument 'adviceClass' must be non-null");
            // all methods that take MetricName check for null, so ok to return null under duress
            return null;
        }
        return metricCache.getMetricName(adviceClass);
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
        if (listener == null) {
            logger.error("registerConfigListener(): argument 'listener' must be non-null");
            return;
        }
        configService.addPluginConfigListener(pluginId, listener);
    }

    @Override
    public Span startTrace(MessageSupplier messageSupplier, MetricName metricName) {
        return startTrace(messageSupplier, metricName, false);
    }

    @Override
    public Span startBackgroundTrace(MessageSupplier messageSupplier, MetricName metricName) {
        return startTrace(messageSupplier, metricName, true);
    }

    private Span startTrace(MessageSupplier messageSupplier, MetricName metricName,
            boolean background) {
        if (messageSupplier == null) {
            logger.error("startTrace(): argument 'messageSupplier' must be non-null");
            return new NopSpan(messageSupplier);
        }
        if (metricName == null) {
            logger.error("startTrace(): argument 'metricName' must be non-null");
            return new NopSpan(messageSupplier);
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            trace = new Trace((MetricNameImpl) metricName, messageSupplier, background,
                    clock.currentTimeMillis(), ticker, weavingMetricName);
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
            return new NopSpan(messageSupplier);
        }
        if (metricName == null) {
            logger.error("startSpan(): argument 'metricName' must be non-null");
            return new NopSpan(messageSupplier);
        }
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            return new NopSpan(messageSupplier);
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
            return new CompletedSpanImpl(trace.addSpan(messageSupplier, null, false));
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
            return new CompletedSpanImpl(trace.addSpan(null, errorMessage, true));
        }
        return NopCompletedSpan.INSTANCE;
    }

    @Override
    public void setUserId(@Nullable String userId) {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.setUserId(userId);
            if (userId != null && trace.getFineProfilingScheduledFuture() == null) {
                fineProfileScheduler.maybeScheduleFineProfilingUsingUserId(trace, userId);
            }
        }
    }

    @Override
    public void setTraceAttribute(String name, @Nullable String value) {
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
        if (pluginId.equals(DYNAMIC_POINTCUTS_PLUGIN_ID)) {
            enabled = generalConfig.isEnabled();
        } else {
            pluginConfig = configService.getPluginConfig(pluginId);
            enabled = generalConfig.isEnabled() && pluginConfig != null && pluginConfig.isEnabled();
        }
        maxSpans = generalConfig.getMaxSpans();
    }

    private Span startSpan(Trace trace, MetricNameImpl metricName,
            MessageSupplier messageSupplier) {
        if (trace.getSpanCount() >= maxSpans) {
            // the span limit has been exceeded for this trace
            trace.addSpanLimitExceededMarkerIfNeeded();
            long startTick = ticker.read();
            Metric metric = metricName.get();
            if (metric == null) {
                metric = trace.addMetric(metricName);
            }
            metric.start(startTick);
            return new TimerWrappedInSpan(metric, startTick, trace, messageSupplier);
        } else {
            return new SpanImpl(trace.pushSpan(metricName, messageSupplier), trace);
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
        private final io.informant.trace.model.Span span;
        private final Trace trace;
        private SpanImpl(io.informant.trace.model.Span span, Trace trace) {
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
                throw new IllegalStateException("Somehow got hold of an error Span??");
            }
            return messageSupplier;
        }
        private CompletedSpan endInternal(long endTick, @Nullable ErrorMessage errorMessage) {
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
                trace.clearThreadLocalMetrics();
            }
            return new CompletedSpanImpl(span);
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
                io.informant.trace.model.Span span = trace.addSpan(messageSupplier, null, true);
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
            metric.stop();
            // use higher span limit when adding errors, but still need some kind of cap
            if (trace.getSpanCount() < 2 * maxSpans) {
                // span won't necessarily be nested properly, and won't have any timing data, but at
                // least the error will get captured
                return new CompletedSpanImpl(trace.addSpan(messageSupplier, errorMessage, true));
            }
            return NopCompletedSpan.INSTANCE;
        }
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
        }
    }

    private static class CompletedSpanImpl implements CompletedSpan {
        private final io.informant.trace.model.Span span;
        private CompletedSpanImpl(io.informant.trace.model.Span span) {
            this.span = span;
        }
        public void captureSpanStackTrace() {
            span.setStackTrace(PluginServicesImpl.captureSpanStackTrace());
        }
    }

    private static class NopSpan implements Span {
        private final MessageSupplier messageSupplier;
        private NopSpan(MessageSupplier messageSupplier) {
            this.messageSupplier = messageSupplier;
        }
        public CompletedSpan end() {
            return NopCompletedSpan.INSTANCE;
        }
        public CompletedSpan endWithStackTrace(long threshold, TimeUnit unit) {
            return NopCompletedSpan.INSTANCE;
        }
        public CompletedSpan endWithError(ErrorMessage errorMessage) {
            return NopCompletedSpan.INSTANCE;
        }
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
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
