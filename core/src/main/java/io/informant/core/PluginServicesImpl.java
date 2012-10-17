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
package io.informant.core;

import io.informant.api.ErrorMessage;
import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.api.MessageSupplier;
import io.informant.api.Metric;
import io.informant.api.PluginServices;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.api.Span;
import io.informant.api.Timer;
import io.informant.core.config.ConfigService;
import io.informant.core.config.CoreConfig;
import io.informant.core.config.FineProfilingConfig;
import io.informant.core.config.PluginConfig;
import io.informant.core.config.UserTracingConfig;
import io.informant.core.trace.FineGrainedProfiler;
import io.informant.core.trace.MetricImpl;
import io.informant.core.trace.TerminateScheduledActionException;
import io.informant.core.trace.Trace;
import io.informant.core.trace.TraceRegistry;
import io.informant.core.trace.TraceSink;
import io.informant.core.trace.WeavingMetricImpl;
import io.informant.core.util.Clock;

import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

/**
 * Implementation of PluginServices from the Plugin API. Each plugin gets its own instance so that
 * isEnabled(), getStringProperty(), etc can be scoped to the given plugin. The pluginId should be
 * "groupId:artifactId", constructed from the plugin's maven coordinates (or at least matching the
 * groupId and artifactId specified in the plugin's io.informant.plugin.xml).
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
    // plugin's io.informant.plugin.xml
    private final String pluginId;

    // cache for fast read access
    private volatile CoreConfig coreConfig;
    private volatile PluginConfig pluginConfig;

    @Inject
    PluginServicesImpl(TraceRegistry traceRegistry, TraceSink traceSink,
            ConfigService configService, MetricCache metricCache,
            FineGrainedProfiler fineGrainedProfiler, Clock clock, Ticker ticker,
            Random random, WeavingMetricImpl weavingMetric, @Assisted String pluginId) {

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
        coreConfig = configService.getCoreConfig();
        pluginConfig = configService.getPluginConfig(pluginId);
    }

    @Override
    public Metric getMetric(Class<?> adviceClass) {
        return metricCache.getMetric(adviceClass);
    }

    @Override
    public boolean isEnabled() {
        return coreConfig.isEnabled() && pluginConfig.isEnabled();
    }

    @Override
    @Nullable
    public String getStringProperty(String propertyName) {
        if (propertyName == null) {
            logger.warn("getStringProperty(): argument 'propertyName' must be non-null");
            return null;
        }
        return pluginConfig.getStringProperty(propertyName);
    }

    @Override
    public boolean getBooleanProperty(String propertyName) {
        if (propertyName == null) {
            logger.warn("getBooleanProperty(): argument 'propertyName' must be non-null");
            return false;
        }
        return pluginConfig.getBooleanProperty(propertyName);
    }

    @Override
    @Nullable
    public Double getDoubleProperty(String propertyName) {
        if (propertyName == null) {
            logger.warn("getDoubleProperty(): argument 'propertyName' must be non-null");
            return null;
        }
        return pluginConfig.getDoubleProperty(propertyName);
    }

    @Override
    public void registerConfigListener(ConfigListener listener) {
        if (listener == null) {
            logger.warn("registerConfigListener(): argument 'listener' must be non-null");
            return;
        }
        configService.addConfigListener(listener);
    }

    @Override
    public Span startTrace(MessageSupplier messageSupplier, Metric metric) {
        return startTrace(messageSupplier, metric, null, false);
    }

    @Override
    public Span startTrace(MessageSupplier messageSupplier, Metric metric,
            @Nullable String userId) {
        return startTrace(messageSupplier, metric, userId, false);
    }

    @Override
    public Span startBackgroundTrace(MessageSupplier messageSupplier, Metric metric) {
        return startTrace(messageSupplier, metric, null, true);
    }

    private Span startTrace(MessageSupplier messageSupplier, Metric metric,
            @Nullable String userId, boolean background) {

        if (messageSupplier == null) {
            logger.warn("startTrace(): argument 'messageSupplier' must be non-null");
            return NopSpan.INSTANCE;
        }
        if (metric == null) {
            logger.warn("startTrace(): argument 'metric' must be non-null");
            return NopSpan.INSTANCE;
        }
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            currentTrace = new Trace((MetricImpl) metric, messageSupplier, clock, ticker,
                    weavingMetric);
            currentTrace.setBackground(background);
            if (!maybeScheduleFineProfilingUsingUserId(currentTrace, userId)) {
                maybeScheduleFineProfilingUsingPercentage(currentTrace);
            }
            traceRegistry.addTrace(currentTrace);
            return new SpanImpl(currentTrace.getRootSpan(), currentTrace);
        } else {
            return startSpan(currentTrace, (MetricImpl) metric, messageSupplier);
        }
    }

    @Override
    public Span startSpan(MessageSupplier messageSupplier, Metric metric) {
        if (messageSupplier == null) {
            logger.warn("startSpan(): argument 'messageSupplier' must be non-null");
            return NopSpan.INSTANCE;
        }
        if (metric == null) {
            logger.warn("startSpan(): argument 'metric' must be non-null");
            return NopSpan.INSTANCE;
        }
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            return NopSpan.INSTANCE;
        } else {
            return startSpan(currentTrace, (MetricImpl) metric, messageSupplier);
        }
    }

    @Override
    public Timer startTimer(Metric metric) {
        if (metric == null) {
            logger.warn("startTimer(): argument 'metric' must be non-null");
            return NopTimer.INSTANCE;
        }
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            // TODO return global collector?
            return NopTimer.INSTANCE;
        } else {
            return currentTrace.startTraceMetric((MetricImpl) metric);
        }
    }

    @Override
    public void addSpan(MessageSupplier messageSupplier) {
        if (messageSupplier == null) {
            logger.warn("addSpan(): argument 'messageSupplier' must be non-null");
            return;
        }
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace != null) {
            int maxSpans = coreConfig.getMaxSpans();
            if (maxSpans == CoreConfig.SPAN_LIMIT_DISABLED
                    || currentTrace.getSpanCount() < maxSpans) {
                // the trace limit has not been exceeded
                currentTrace.addSpan(messageSupplier, null);
            }
        }
    }

    @Override
    public void addErrorSpan(ErrorMessage errorMessage) {
        if (errorMessage == null) {
            logger.warn("addErrorSpan(): argument 'errorMessage' must be non-null");
            return;
        }
        // don't use span limit when adding errors
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace != null) {
            currentTrace.addSpan(null, errorMessage);
        }
    }

    // it is better to use startTrace(..., userId) so that fine profiling can be scheduled from the
    // beginning of the trace in case user tracing with fine profiling is configured for this user,
    // but if that's not possible or efficient then this method will still schedule fine profiling
    // from this point forward if applicable
    @Override
    public void setUserId(@Nullable String userId) {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.setUserId(userId);
            if (trace.getFineProfilingScheduledFuture() == null) {
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
            trace.setAttribute(name, value);
        }
    }

    @Override
    @Nullable
    public MessageSupplier getRootMessageSupplier() {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            return null;
        } else {
            return trace.getRootSpan().getMessageSupplier();
        }
    }

    public void onChange() {
        coreConfig = configService.getCoreConfig();
        pluginConfig = configService.getPluginConfig(pluginId);
    }

    private boolean maybeScheduleFineProfilingUsingUserId(Trace currentTrace,
            @Nullable String userId) {
        if (userId == null) {
            return false;
        }
        UserTracingConfig userTracingConfig = configService.getUserTracingConfig();
        if (userTracingConfig.isEnabled() && userTracingConfig.isFineProfiling()
                && userId.equals(userTracingConfig.getUserId())) {
            fineGrainedProfiler.scheduleProfiling(currentTrace);
            return true;
        } else {
            return false;
        }
    }

    private void maybeScheduleFineProfilingUsingPercentage(Trace currentTrace) {
        FineProfilingConfig fineProfilingConfig = configService.getFineProfilingConfig();
        if (fineProfilingConfig.isEnabled()
                && random.nextDouble() * 100 < fineProfilingConfig.getTracePercentage()) {
            fineGrainedProfiler.scheduleProfiling(currentTrace);
        }
    }

    // TODO how to escalate TimerWrappedInSpan afterwards if WARN/ERROR
    private Span startSpan(Trace currentTrace, MetricImpl metric,
            MessageSupplier messageSupplier) {

        int maxSpans = coreConfig.getMaxSpans();
        if (maxSpans != CoreConfig.SPAN_LIMIT_DISABLED && currentTrace.getSpanCount() >= maxSpans) {
            // the trace limit has been exceeded
            return new TimerWrappedInSpan(currentTrace, metric, messageSupplier);
        } else {
            return new SpanImpl(currentTrace.pushSpan(metric, messageSupplier), currentTrace);
        }
    }

    @NotThreadSafe
    private class SpanImpl implements Span {
        private final io.informant.core.trace.Span span;
        private final Trace currentTrace;
        private SpanImpl(io.informant.core.trace.Span span, Trace currentTrace) {
            this.span = span;
            this.currentTrace = currentTrace;
        }
        public void end() {
            endInternal(null);
        }
        public void endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.warn("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                end();
            } else {
                endInternal(errorMessage);
            }
        }
        public void updateMessage(MessageUpdater updater) {
            if (updater == null) {
                logger.warn("updateMessage(): argument 'updater' must be non-null");
            } else {
                updater.update(span.getMessageSupplier());
            }
        }
        private void endInternal(ErrorMessage errorMessage) {
            long endTick = ticker.read();
            if (endTick - span.getStartTick() >= TimeUnit.MILLISECONDS.toNanos(coreConfig
                    .getSpanStackTraceThresholdMillis())) {
                span.setStackTrace(captureSpanStackTrace());
            }
            currentTrace.popSpan(span, endTick, errorMessage);
            if (currentTrace.isCompleted()) {
                // the root span has been popped off
                // since the metrics are bound to the thread, they need to be recorded and reset
                // while still in the trace thread, before the thread is reused for another trace
                currentTrace.clearThreadLocalMetrics();
                cancelScheduledFuture(currentTrace.getCoarseProfilingScheduledFuture());
                cancelScheduledFuture(currentTrace.getStuckScheduledFuture());
                cancelScheduledFuture(currentTrace.getFineProfilingScheduledFuture());
                // send to trace sink before removing from trace registry so that trace sink
                // can cover the gap (via TraceSink.getPendingTraces()) between removing the trace
                // from the registry and storing it
                traceSink.onCompletedTrace(currentTrace);
                traceRegistry.removeTrace(currentTrace);
            }
        }
        private StackTraceElement[] captureSpanStackTrace() {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            // need to strip back 5 stack calls:
            // 1 - java.lang.Thread.getStackTrace()
            // 2 - io.informant.core.PluginServicesImpl$SpanImpl.captureSpanStackTrace()
            // 3 - io.informant.core.PluginServicesImpl$SpanImpl.endInternal()
            // 4 - io.informant.core.PluginServicesImpl$SpanImpl.end()/endWithError()
            // 5 - the @Pointcut @OnReturn/@OnThrow/@OnAfter method
            // (possibly more if the @Pointcut method doesn't call end()/endWithError() directly)
            StackTraceElement[] spanStackTrace = new StackTraceElement[stackTrace.length - 5];
            System.arraycopy(stackTrace, 5, spanStackTrace, 0, spanStackTrace.length);
            return spanStackTrace;
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
                        logger.error(e.getMessage(), e.getCause());
                    }
                }
            }
        }
    }

    @NotThreadSafe
    private static class TimerWrappedInSpan implements Span {
        private final Trace currentTrace;
        private final MessageSupplier messageSupplier;
        private final Timer timer;
        public TimerWrappedInSpan(Trace currentTrace, MetricImpl metric,
                MessageSupplier messageSupplier) {
            this.currentTrace = currentTrace;
            this.messageSupplier = messageSupplier;
            timer = currentTrace.startTraceMetric(metric);
        }
        public void end() {
            timer.end();
        }
        public void endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.warn("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                end();
                return;
            }
            timer.end();
            // span won't necessarily be nested properly, and won't have any timing data, but at
            // least the error will get captured
            currentTrace.addSpan(messageSupplier, errorMessage);
        }
        public void updateMessage(MessageUpdater updater) {
            if (updater == null) {
                logger.warn("updateMessage(): argument 'updater' must be non-null");
                return;
            }
            updater.update(messageSupplier);
        }
    }

    @ThreadSafe
    private static class NopSpan implements Span {
        private static final NopSpan INSTANCE = new NopSpan();
        public void end() {}
        public void endWithError(ErrorMessage errorMessage) {}
        public void updateMessage(MessageUpdater updater) {}
    }

    @ThreadSafe
    private static class NopTimer implements Timer {
        private static final NopTimer INSTANCE = new NopTimer();
        public void end() {}
    }

    interface PluginServicesImplFactory {
        PluginServicesImpl create(String pluginId);
    }
}
