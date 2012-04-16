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
package org.informantproject.core.trace;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.informantproject.api.Metric;
import org.informantproject.api.Optional;
import org.informantproject.api.PluginServices;
import org.informantproject.api.PluginServices.ConfigurationListener;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.Span;
import org.informantproject.api.SpanDetail;
import org.informantproject.api.TraceMetric;
import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.ImmutableCoreConfiguration;
import org.informantproject.core.configuration.ImmutablePluginConfiguration;
import org.informantproject.core.metric.MetricCache;
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
public class PluginServicesImpl extends PluginServices implements ConfigurationListener {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TraceRegistry traceRegistry;
    private final TraceSink traceSink;
    private final ConfigurationService configurationService;
    private final MetricCache metricCache;
    private final Clock clock;
    private final Ticker ticker;

    // pluginId should be "groupId:artifactId", based on the groupId and artifactId specified in the
    // plugin's org.informantproject.plugin.xml
    private final String pluginId;

    // cache for fast read access
    private volatile ImmutableCoreConfiguration coreConfiguration;
    private volatile ImmutablePluginConfiguration pluginConfiguration;

    @Inject
    PluginServicesImpl(TraceRegistry traceRegistry, TraceSink traceSink,
            ConfigurationService configurationService, MetricCache metricCache, Clock clock,
            Ticker ticker, @Assisted String pluginId) {

        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.configurationService = configurationService;
        this.metricCache = metricCache;
        this.clock = clock;
        this.ticker = ticker;
        this.pluginId = pluginId;
        // add configuration listener first before caching configuration properties to avoid a
        // (remotely) possible race condition
        configurationService.addConfigurationListener(this);
        coreConfiguration = configurationService.getCoreConfiguration();
        pluginConfiguration = configurationService.getPluginConfiguration(pluginId);
    }

    @Override
    public Metric getMetric(Class<?> adviceClass) {
        return metricCache.getMetric(adviceClass);
    }

    @Override
    public boolean isEnabled() {
        return coreConfiguration.isEnabled() && pluginConfiguration.isEnabled();
    }

    @Override
    public Optional<String> getStringProperty(String propertyName) {
        return pluginConfiguration.getStringProperty(propertyName);
    }

    @Override
    public boolean getBooleanProperty(String propertyName) {
        return pluginConfiguration.getBooleanProperty(propertyName);
    }

    @Override
    public Optional<Double> getDoubleProperty(String propertyName) {
        return pluginConfiguration.getDoubleProperty(propertyName);
    }

    @Override
    public void registerConfigurationListener(ConfigurationListener listener) {
        configurationService.addConfigurationListener(listener);
    }

    @Override
    public Span startRootSpan(Metric metric, RootSpanDetail rootSpanDetail) {
        return startSpan(traceRegistry.getCurrentTrace(), (MetricImpl) metric, rootSpanDetail);
    }

    @Override
    public Span startSpan(Metric metric, SpanDetail spanDetail) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            return IgnoreSpan.INSTANCE;
        } else {
            return startSpan(currentTrace, (MetricImpl) metric, spanDetail);
        }
    }

    @Override
    public void endSpan(Span span) {
        if (span instanceof SpanImpl) {
            endSpanAndMetric((SpanImpl) span);
        } else if (span instanceof LimitDisabledSpan) {
            endMetric(((LimitDisabledSpan) span).traceMetric);
        } else if (span instanceof IgnoreSpan) {
            // do nothing
        } else {
            logger.error("unexpected span type '{}'", span.getClass().getName());
        }
    }

    @Override
    public TraceMetricImpl startMetric(Metric metric) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            // TODO return global collector?
            return null;
        } else {
            return currentTrace.startTraceMetric((MetricImpl) metric);
        }
    }

    @Override
    public void endMetric(TraceMetric traceMetric) {
        if (traceMetric != null) {
            // TODO in the future a global collector will be passed instead of null and no
            // conditional will be needed
            ((TraceMetricImpl) traceMetric).stop();
        }
    }

    @Override
    public void putTraceAttribute(String name, String value) {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace != null) {
            trace.putAttribute(name, Optional.fromNullable(value));
        }
    }

    @Override
    public RootSpanDetail getRootSpanDetail() {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            return null;
        } else {
            return (RootSpanDetail) trace.getRootSpan().getRootSpan().getSpanDetail();
        }
    }

    public void onChange() {
        coreConfiguration = configurationService.getCoreConfiguration();
        pluginConfiguration = configurationService.getPluginConfiguration(pluginId);
    }

    private void endSpanAndMetric(SpanImpl span) {
        // minimizing the number of calls to the clock timer as they are relatively expensive
        long endTick = ticker.read();
        // pop span needs to be the last step (at least when this is a root span)
        popSpan(span, endTick);
    }

    private Span startSpan(Trace currentTrace, MetricImpl metric, SpanDetail spanDetail) {
        if (currentTrace == null) {
            currentTrace = new Trace(metric, spanDetail, clock, ticker);
            traceRegistry.setCurrentTrace(currentTrace);
            traceRegistry.addTrace(currentTrace);
            return currentTrace.getRootSpan().getRootSpan();
        } else {
            int maxSpansPerTrace = coreConfiguration.getMaxSpansPerTrace();
            if (maxSpansPerTrace != ImmutableCoreConfiguration.SPAN_LIMIT_DISABLED
                    && currentTrace.getRootSpan().getSize() >= maxSpansPerTrace) {
                // the trace limit has been exceeded
                TraceMetricImpl traceMetric = startMetric(metric);
                return new LimitDisabledSpan(traceMetric);
            } else {
                return currentTrace.pushSpan(metric, spanDetail);
            }
        }
    }

    // typically pop() methods don't require the span to pop, but for safety,
    // the span to pop is passed in just to make sure it is the one on top
    // (and if it is not the one on top, then pop until it is found, preventing
    // any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    private void popSpan(SpanImpl span, long endTick) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        StackTraceElement[] stackTraceElements = null;
        if (endTick - span.getStartTick() >= TimeUnit.MILLISECONDS.toNanos(coreConfiguration
                .getSpanStackTraceThresholdMillis())) {
            stackTraceElements = Thread.currentThread().getStackTrace();
            // TODO remove last few stack trace elements?
        }
        currentTrace.popSpan(span, endTick, stackTraceElements);
        if (currentTrace.isCompleted()) {
            // the root span has been popped off
            // since the metrics are bound to the thread, they need to be recorded and reset while
            // still in the trace thread, before the thread is reused for another trace
            currentTrace.resetThreadLocalMetrics();
            cancelScheduledFuture(currentTrace.getCaptureStackTraceScheduledFuture());
            cancelScheduledFuture(currentTrace.getStuckCommandScheduledFuture());
            traceRegistry.setCurrentTrace(null);
            traceRegistry.removeTrace(currentTrace);
            traceSink.onCompletedTrace(currentTrace);
        }
    }

    private static void cancelScheduledFuture(ScheduledFuture<?> scheduledFuture) {
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

    private static class LimitDisabledSpan implements Span {
        private final TraceMetricImpl traceMetric;
        private LimitDisabledSpan(TraceMetricImpl traceMetric) {
            this.traceMetric = traceMetric;
        }
    }

    private static class IgnoreSpan implements Span {
        private static final IgnoreSpan INSTANCE = new IgnoreSpan();
    }

    public interface PluginServicesImplFactory {
        PluginServicesImpl create(String pluginId);
    }
}
