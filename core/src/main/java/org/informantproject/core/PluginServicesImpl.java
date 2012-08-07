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
package org.informantproject.core;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.informantproject.api.Message;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.PluginServices.ConfigListener;
import org.informantproject.api.Span;
import org.informantproject.api.Supplier;
import org.informantproject.api.Timer;
import org.informantproject.core.config.ConfigService;
import org.informantproject.core.config.CoreConfig;
import org.informantproject.core.config.PluginConfig;
import org.informantproject.core.metric.MetricCache;
import org.informantproject.core.trace.MetricImpl;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.trace.TraceSink;
import org.informantproject.core.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

/**
 * Implementation of PluginServices from the Plugin API. Each plugin gets its own instance so that
 * isEnabled(), getStringProperty(), etc can be scoped to the given plugin. The pluginId should be
 * "groupId:artifactId", constructed from the plugin's maven coordinates (or at least matching the
 * groupId and artifactId specified in the plugin's org.informantproject.plugin.xml).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class PluginServicesImpl extends PluginServices implements ConfigListener {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TraceRegistry traceRegistry;
    private final TraceSink traceSink;
    private final ConfigService configService;
    private final MetricCache metricCache;
    private final Clock clock;
    private final Ticker ticker;

    // pluginId should be "groupId:artifactId", based on the groupId and artifactId specified in the
    // plugin's org.informantproject.plugin.xml
    private final String pluginId;

    // cache for fast read access
    private volatile CoreConfig coreConfig;
    private volatile PluginConfig pluginConfig;

    @Inject
    PluginServicesImpl(TraceRegistry traceRegistry, TraceSink traceSink,
            ConfigService configService, MetricCache metricCache, Clock clock, Ticker ticker,
            @Assisted String pluginId) {

        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.configService = configService;
        this.metricCache = metricCache;
        this.clock = clock;
        this.ticker = ticker;
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
        return pluginConfig.getStringProperty(propertyName);
    }

    @Override
    public boolean getBooleanProperty(String propertyName) {
        return pluginConfig.getBooleanProperty(propertyName);
    }

    @Override
    @Nullable
    public Double getDoubleProperty(String propertyName) {
        return pluginConfig.getDoubleProperty(propertyName);
    }

    @Override
    public void registerConfigListener(ConfigListener listener) {
        configService.addConfigListener(listener);
    }

    @Override
    public Span startTrace(Supplier<Message> messageSupplier, Metric metric) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            currentTrace = new Trace((MetricImpl) metric, messageSupplier, clock, ticker);
            traceRegistry.addTrace(currentTrace);
            return new SpanImpl(currentTrace.getRootSpan().getRootSpan(), currentTrace);
        } else {
            return startSpan(currentTrace, (MetricImpl) metric, messageSupplier);
        }
    }

    @Override
    public Span startSpan(Supplier<Message> messageSupplier, Metric metric) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            return NopSpan.INSTANCE;
        } else {
            return startSpan(currentTrace, (MetricImpl) metric, messageSupplier);
        }
    }

    @Override
    public Timer startTimer(Metric metric) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            // TODO return global collector?
            return NopTimer.INSTANCE;
        } else {
            return currentTrace.startTraceMetric((MetricImpl) metric);
        }
    }

    @Override
    public void addSpan(Supplier<Message> messageSupplier) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace != null) {
            int maxEntries = coreConfig.getMaxEntries();
            if (maxEntries == CoreConfig.SPAN_LIMIT_DISABLED
                    || currentTrace.getRootSpan().getSize() < maxEntries) {
                // the trace limit has not been exceeded
                currentTrace.addSpan(messageSupplier, false);
            }
        }
    }

    @Override
    public void addErrorSpan(Supplier<Message> messageSupplier, @Nullable Throwable t) {
        // don't use span limit when adding errors
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace != null) {
            org.informantproject.core.trace.Span span = currentTrace.addSpan(messageSupplier, true);
            if (t != null) {
                span.setStackTraceElements(t.getStackTrace());
            }
        }
    }

    @Override
    public void setUsername(Supplier<String> username) {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.setUsername(username);
        }
    }

    @Override
    public void putTraceAttribute(String name, @Nullable String value) {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.putAttribute(name, value);
        }
    }

    @Override
    @Nullable
    public Supplier<Message> getRootMessageSupplier() {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            return null;
        } else {
            return trace.getRootSpan().getRootSpan().getMessageSupplier();
        }
    }

    public void onChange() {
        coreConfig = configService.getCoreConfig();
        pluginConfig = configService.getPluginConfig(pluginId);
    }

    private Span startSpan(Trace currentTrace, MetricImpl metric,
            Supplier<Message> messageSupplier) {

        int maxEntries = coreConfig.getMaxEntries();
        if (maxEntries != CoreConfig.SPAN_LIMIT_DISABLED
                && currentTrace.getRootSpan().getSize() >= maxEntries) {
            // the trace limit has been exceeded
            return new TimerWrappedInSpan(startTimer(metric));
        } else {
            return new SpanImpl(currentTrace.pushSpan(metric, messageSupplier), currentTrace);
        }
    }

    private class SpanImpl implements Span {
        private final org.informantproject.core.trace.Span span;
        private final Trace currentTrace;
        private SpanImpl(org.informantproject.core.trace.Span span, Trace currentTrace) {
            this.span = span;
            this.currentTrace = currentTrace;
        }
        public void endWithError(@Nullable Throwable t) {
            stopInternal(true, t);
        }
        public void end() {
            stopInternal(false, null);
        }
        public void updateMessage(MessageUpdater updater) {
            updater.update(span.getMessageSupplier());
        }
        private void stopInternal(boolean error, @Nullable Throwable t) {
            long endTick = ticker.read();
            if (t != null) {
                span.setStackTraceElements(t.getStackTrace());
            } else if (endTick - span.getStartTick() >= TimeUnit.MILLISECONDS.toNanos(coreConfig
                    .getSpanStackTraceThresholdMillis())) {
                // TODO remove last few stack trace elements?
                span.setStackTraceElements(Thread.currentThread().getStackTrace());
            }
            currentTrace.popSpan(span, endTick, error);
            if (currentTrace.isCompleted()) {
                // the root span has been popped off
                // since the metrics are bound to the thread, they need to be recorded and reset
                // while still in the trace thread, before the thread is reused for another trace
                currentTrace.resetThreadLocalMetrics();
                cancelScheduledFuture(currentTrace.getCaptureStackTraceScheduledFuture());
                cancelScheduledFuture(currentTrace.getStuckCommandScheduledFuture());
                traceRegistry.removeTrace(currentTrace);
                traceSink.onCompletedTrace(currentTrace);
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
                    logger.error(e.getMessage(), e.getCause());
                }
            }
        }
    }

    private static class TimerWrappedInSpan implements Span {
        private final Timer timer;
        private TimerWrappedInSpan(Timer timer) {
            this.timer = timer;
        }
        public void end() {
            timer.end();
        }
        public void endWithError(@Nullable Throwable t) {
            timer.end();
        }
        public void updateMessage(MessageUpdater updater) {}
    }

    private static class NopSpan implements Span {
        private static final NopSpan INSTANCE = new NopSpan();
        public void end() {}
        public void endWithError(@Nullable Throwable t) {}
        public void updateMessage(MessageUpdater updater) {}
    }

    private static class NopTimer implements Timer {
        private static final NopTimer INSTANCE = new NopTimer();
        public void end() {}
    }

    interface PluginServicesImplFactory {
        PluginServicesImpl create(String pluginId);
    }
}
