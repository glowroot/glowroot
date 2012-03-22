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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.informantproject.api.Optional;
import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanDetail;
import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.ConfigurationService.ConfigurationListener;
import org.informantproject.core.configuration.ImmutableCoreConfiguration;
import org.informantproject.core.util.Clock;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
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
    private final Clock clock;
    private final Ticker ticker;

    // pluginId should be "groupId:artifactId", based on the groupId and artifactId specified in the
    // plugin's org.informantproject.plugin.xml
    private final String pluginId;

    // cache enabled status and plugin properties for slightly faster lookup (and for not having to
    // convert guava Optional to informant Optional every time)
    private volatile boolean enabled;

    private final LoadingCache<String, Optional<String>> stringProperties = CacheBuilder
            .newBuilder().build(new CacheLoader<String, Optional<String>>() {
                @Override
                public Optional<String> load(String propertyName) throws Exception {
                    return configurationService.getPluginConfiguration(pluginId).getStringProperty(
                            propertyName);
                }
            });

    private final LoadingCache<String, Boolean> booleanProperties = CacheBuilder
            .newBuilder().build(new CacheLoader<String, Boolean>() {
                @Override
                public Boolean load(String propertyName) throws Exception {
                    return configurationService.getPluginConfiguration(pluginId)
                            .getBooleanProperty(propertyName);
                }
            });

    private final LoadingCache<String, Optional<Double>> doubleProperties = CacheBuilder
            .newBuilder().build(new CacheLoader<String, Optional<Double>>() {
                @Override
                public Optional<Double> load(String propertyName) throws Exception {
                    return configurationService.getPluginConfiguration(pluginId).getDoubleProperty(
                            propertyName);
                }
            });

    @Inject
    PluginServicesImpl(TraceRegistry traceRegistry, TraceSink traceSink,
            ConfigurationService configurationService, Clock clock, Ticker ticker,
            @Assisted String pluginId) {

        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.configurationService = configurationService;
        this.clock = clock;
        this.ticker = ticker;
        this.pluginId = pluginId;
        // add configuration listener first before caching configuration properties to avoid a
        // (remotely) possible race condition
        configurationService.addConfigurationListener(this);
        enabled = configurationService.getCoreConfiguration().isEnabled() && configurationService
                .getPluginConfiguration(pluginId).isEnabled();
    }

    @Override
    public boolean isEnabled() {
        // this method should have as little overhead as possible so don't even call logger
        return this.enabled && !traceRegistry.isCurrentRootSpanDisabled();
    }

    @Override
    public Optional<String> getStringProperty(String propertyName) {
        logger.debug("getStringProperty(): propertyName={}", propertyName);
        return stringProperties.getUnchecked(propertyName);
    }

    @Override
    public boolean getBooleanProperty(String propertyName) {
        logger.debug("getBooleanProperty(): propertyName={}", propertyName);
        return booleanProperties.getUnchecked(propertyName);
    }

    @Override
    public Optional<Double> getDoubleProperty(String propertyName) {
        logger.debug("getDoubleProperty(): propertyName={}", propertyName);
        return doubleProperties.getUnchecked(propertyName);
    }

    @Override
    public Object executeRootSpan(String spanName, RootSpanDetail rootSpanDetail,
            ProceedingJoinPoint joinPoint) throws Throwable {

        logger.debug("executeRootSpan(): spanName={}", spanName);
        ImmutableCoreConfiguration configuration = configurationService.getCoreConfiguration();
        // this should be the first check to avoid any additional overhead when tracing is disabled
        if (!configuration.isEnabled()) {
            return proceedAndDisableNested(joinPoint);
        }
        return proceedAndRecordSpanAndMetricData(spanName, rootSpanDetail, joinPoint);
    }

    @Override
    public Object executeSpan(String spanName, SpanDetail spanDetail, ProceedingJoinPoint joinPoint)
            throws Throwable {

        logger.debug("executeSpan(): spanName={}", spanName);
        ImmutableCoreConfiguration configuration = configurationService.getCoreConfiguration();
        // this should be the first check to avoid any additional overhead when tracing is disabled
        // (this conditional includes the edge case where tracing was disabled mid-trace)
        if (!configuration.isEnabled()) {
            return proceedAndDisableNested(joinPoint);
        }
        if (!isInTrace()) {
            if (configuration.isWarnOnSpanOutsideTrace()) {
                logger.warn("span encountered outside of trace", new IllegalStateException());
            }
            return proceedAndDisableNested(joinPoint);
        }
        if (traceRegistry.isCurrentRootSpanDisabled()) {
            // tracing was enabled after the current trace had started
            return proceedAndRecordMetricData(spanName, joinPoint);
        }
        Trace currentTrace = traceRegistry.getCurrentTrace();
        // already checked isInTrace() above, so currentTrace != null
        if (configuration.getMaxSpansPerTrace() != ImmutableCoreConfiguration.SPAN_LIMIT_DISABLED
                && currentTrace.getRootSpan().getSize() >= configuration.getMaxSpansPerTrace()) {
            // the trace limit has been exceeded
            return proceedAndRecordMetricData(spanName, joinPoint);
        }
        return proceedAndRecordSpanAndMetricData(spanName, spanDetail, joinPoint);
    }

    @Override
    public Object proceedAndRecordMetricData(String spanName, final ProceedingJoinPoint joinPoint)
            throws Throwable {

        return proceedAndRecordMetricData(spanName,
                new CallableWithThrowable<Object, Throwable>() {
                    public Object call() throws Throwable {
                        return joinPoint.proceed();
                    }
                });
    }

    @Override
    public <V> V proceedAndRecordMetricData(String spanName, final Callable<V> callable)
            throws Exception {

        return proceedAndRecordMetricData(spanName,
                new CallableWithThrowable<V, Exception>() {
                    public V call() throws Exception {
                        return callable.call();
                    }
                });
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
        enabled = configurationService.getCoreConfiguration().isEnabled() && configurationService
                .getPluginConfiguration(pluginId).isEnabled();
        stringProperties.invalidateAll();
        booleanProperties.invalidateAll();
        doubleProperties.invalidateAll();
    }

    private Object proceedAndDisableNested(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean previouslyDisabled = traceRegistry.isCurrentRootSpanDisabled();
        try {
            // disable current trace so that nested spans will not be captured even
            // if tracing is re-enabled mid-trace
            traceRegistry.setCurrentRootSpanDisabled(true);
            return joinPoint.proceed();
        } finally {
            traceRegistry.setCurrentRootSpanDisabled(previouslyDisabled);
        }
    }

    private boolean isInTrace() {
        return traceRegistry.getCurrentTrace() != null;
    }

    private Object proceedAndRecordSpanAndMetricData(String spanName, SpanDetail spanDetail,
            ProceedingJoinPoint joinPoint) throws Throwable {

        // start span
        Span span = pushSpan(spanName, spanDetail);
        try {
            return joinPoint.proceed();
        } finally {
            // minimizing the number of calls to the clock timer as they are relatively expensive
            long endTick = ticker.read();
            // pop span needs to be the last step (at least when this is a root span)
            popSpan(spanName, span, endTick);
        }
    }

    private <V, T extends Throwable> V proceedAndRecordMetricData(String spanName,
            CallableWithThrowable<V, T> callable) throws T {

        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            return callable.call();
        } else {
            boolean alreadyPresent = currentTrace.pushSpanWithoutDetail(spanName);
            if (alreadyPresent) {
                return callable.call();
            } else {
                long startTick = ticker.read();
                try {
                    return callable.call();
                } finally {
                    popSpanWithoutDetail(spanName, ticker.read() - startTick);
                }
            }
        }
    }

    // it is very important that calls to pushSpan() are wrapped in try block with
    // a finally block executing popSpan()
    private Span pushSpan(String spanName, SpanDetail spanDetail) {
        // span limit is handled inside PluginServicesImpl
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            currentTrace = new Trace(spanName, spanDetail, clock, ticker);
            traceRegistry.setCurrentTrace(currentTrace);
            traceRegistry.addTrace(currentTrace);
            return currentTrace.getRootSpan().getRootSpan();
        } else {
            return currentTrace.pushSpan(spanName, spanDetail);
        }
    }

    // typically pop() methods don't require the span to pop, but for safety,
    // the span to pop is passed in just to make sure it is the one on top
    // (and if it is not the one on top, then pop until it is found, preventing
    // any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    private void popSpan(String spanName, Span span, long endTick) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        StackTraceElement[] stackTraceElements = null;
        if (endTick - span.getStartTick() >= TimeUnit.MILLISECONDS.toNanos(configurationService
                .getCoreConfiguration().getSpanStackTraceThresholdMillis())) {
            stackTraceElements = Thread.currentThread().getStackTrace();
            // TODO remove last few stack trace elements?
        }
        currentTrace.popSpan(spanName, span, endTick, stackTraceElements);
        if (currentTrace.isCompleted()) {
            // the root span has been popped off
            cancelScheduledFuture(currentTrace.getCaptureStackTraceScheduledFuture());
            cancelScheduledFuture(currentTrace.getStuckCommandScheduledFuture());
            traceRegistry.setCurrentTrace(null);
            traceRegistry.removeTrace(currentTrace);
            traceSink.onCompletedTrace(currentTrace);
        }
    }

    private void popSpanWithoutDetail(String spanName, long duration) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        currentTrace.popSpanWithoutDetail(spanName, duration);
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

    public interface PluginServicesImplFactory {
        PluginServicesImpl create(String pluginId);
    }

    private interface CallableWithThrowable<V, T extends Throwable> {
        V call() throws T;
    }
}
