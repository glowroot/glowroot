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

import java.util.LinkedList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanDetail;
import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.ImmutableCoreConfiguration;
import org.informantproject.core.util.Clock;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.inject.Inject;

/**
 * Implementation of PluginServices from the Plugin API. Each plugin gets its own instance so that
 * isEnabled(), getStringProperty(), etc can be scoped to the given plugin. The pluginId should be
 * "groupId:artifactId", constructed from the plugin's maven coordinates (or at least matching the
 * groupId and artifactId specified in the plugin's org.informantproject.plugin.xml).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginServicesImpl extends PluginServices {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    // pluginId should be "groupId:artifactId", based on the groupId and artifactId specified in the
    // plugin's org.informantproject.plugin.xml
    private final String pluginId;

    private final TraceRegistry traceRegistry;
    private final TraceSink traceSink;
    private final ConfigurationService configurationService;
    private final Clock clock;
    private final Ticker ticker;

    // used to prevent recording overlapping metric timings for the same span summary key
    private final ThreadLocal<LinkedList<String>> spanSummaryKeyStack =
            new ThreadLocal<LinkedList<String>>() {
                @Override
                protected LinkedList<String> initialValue() {
                    // ok to use non-thread safe structure since only accessed by single thread
                    return new LinkedList<String>();
                }
            };

    @Inject
    PluginServicesImpl(String pluginId, TraceRegistry traceRegistry, TraceSink traceSink,
            ConfigurationService configurationService, Clock clock, Ticker ticker) {

        this.pluginId = pluginId;
        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.configurationService = configurationService;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Override
    public String getStringProperty(String propertyName) {
        logger.debug("getStringProperty(): propertyName={}", propertyName);
        return configurationService.getPluginConfiguration().getStringProperty(pluginId,
                propertyName);
    }

    @Override
    public Boolean getBooleanProperty(String propertyName, Boolean defaultValue) {
        logger.debug("getBooleanProperty(): propertyName={}", propertyName);
        return configurationService.getPluginConfiguration().getBooleanProperty(pluginId,
                propertyName, defaultValue);
    }

    @Override
    public Object executeRootSpan(RootSpanDetail rootSpanDetail, ProceedingJoinPoint joinPoint,
            String spanSummaryKey) throws Throwable {

        logger.debug("executeRootSpan(): spanSummaryKey={}", spanSummaryKey);
        ImmutableCoreConfiguration configuration = configurationService.getCoreConfiguration();
        // this should be the first check to avoid any additional overhead when tracing is disabled
        if (!configuration.isEnabled()) {
            return proceedAndDisableNested(joinPoint);
        }
        return proceedAndRecordSpanAndMetricData(rootSpanDetail, joinPoint, spanSummaryKey);
    }

    @Override
    public Object executeSpan(SpanDetail spanDetail, ProceedingJoinPoint joinPoint,
            String spanSummaryKey) throws Throwable {

        logger.debug("executeSpan(): spanSummaryKey={}", spanSummaryKey);
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
            return proceedAndRecordMetricData(joinPoint, spanSummaryKey);
        }
        Trace currentTrace = traceRegistry.getCurrentTrace();
        // already checked isInTrace() above, so currentTrace != null
        if (configuration.getMaxSpansPerTrace() != ImmutableCoreConfiguration.SPAN_LIMIT_DISABLED
                && currentTrace.getRootSpan().getSize() >= configuration.getMaxSpansPerTrace()) {
            // the trace limit has been exceeded
            return proceedAndRecordMetricData(joinPoint, spanSummaryKey);
        }
        return proceedAndRecordSpanAndMetricData(spanDetail, joinPoint, spanSummaryKey);
    }

    @Override
    public Object proceedAndRecordMetricData(ProceedingJoinPoint joinPoint, String spanSummaryKey)
            throws Throwable {

        logger.debug("proceedAndRecordMetricData(): summaryKey={}", spanSummaryKey);
        boolean skipSummaryData = spanSummaryKeyStack.get().contains(spanSummaryKey);
        if (skipSummaryData) {
            return joinPoint.proceed();
        } else {
            spanSummaryKeyStack.get().add(spanSummaryKey);
            long startTick = ticker.read();
            try {
                return joinPoint.proceed();
            } finally {
                long endTick = ticker.read();
                spanSummaryKeyStack.get().removeLast();
                // record aggregate timing data
                recordSummaryData(spanSummaryKey, endTick - startTick);
            }
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

    @Override
    public boolean isEnabled() {
        boolean enabled = configurationService.getCoreConfiguration().isEnabled()
                && !traceRegistry.isCurrentRootSpanDisabled();
        logger.debug("isEnabled(): enabled={}", enabled);
        return enabled;
    }

    private Object proceedAndRecordSpanAndMetricData(SpanDetail spanDetail,
            ProceedingJoinPoint joinPoint, String spanSummaryKey) throws Throwable {

        // start span
        Span span = pushSpan(spanDetail);
        boolean skipSummaryData = spanSummaryKey == null
                || spanSummaryKeyStack.get().contains(spanSummaryKey);
        if (!skipSummaryData) {
            spanSummaryKeyStack.get().add(spanSummaryKey);
        }
        try {
            return joinPoint.proceed();
        } finally {
            // minimizing the number of calls to the clock timer as they are relatively expensive
            long endTick = ticker.read();
            if (!skipSummaryData) {
                spanSummaryKeyStack.get().removeLast();
                // record aggregate timing data
                if (spanSummaryKey != null) {
                    recordSummaryData(spanSummaryKey, endTick - span.getStartTick());
                }
            }
            // pop span needs to be the last step (at least when this is a root span)
            popSpan(span, endTick);
        }
    }

    private boolean isInTrace() {
        return traceRegistry.getCurrentTrace() != null;
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

    // it is very important that calls to pushSpan() are wrapped in try block with
    // a finally block executing popSpan()
    private Span pushSpan(SpanDetail spanDetail) {
        // span limit is handled inside PluginServicesImpl
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            currentTrace = new Trace(spanDetail, clock, ticker);
            traceRegistry.setCurrentTrace(currentTrace);
            traceRegistry.addTrace(currentTrace);
            return currentTrace.getRootSpan().getRootSpan();
        } else {
            return currentTrace.getRootSpan().pushSpan(spanDetail);
        }
    }

    // typically pop() methods don't require the span to pop, but for safety,
    // the span to pop is passed in just to make sure it is the one on top
    // (and if it is not the one on top, then pop until it is found, preventing
    // any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    private void popSpan(Span span, long endTick) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        StackTraceElement[] stackTraceElements = null;
        if (endTick - span.getStartTick() >= TimeUnit.MILLISECONDS.toNanos(configurationService
                .getCoreConfiguration().getSpanStackTraceThresholdMillis())) {
            stackTraceElements = Thread.currentThread().getStackTrace();
            // TODO remove last few stack trace elements?
        }
        currentTrace.getRootSpan().popSpan(span, endTick, stackTraceElements);
        if (currentTrace.isCompleted()) {
            // the root span has been popped off
            cancelScheduledFuture(currentTrace.getCaptureStackTraceScheduledFuture());
            cancelScheduledFuture(currentTrace.getStuckCommandScheduledFuture());
            traceRegistry.setCurrentTrace(null);
            traceRegistry.removeTrace(currentTrace);
            traceSink.onCompletedTrace(currentTrace);
        }
    }

    private void recordSummaryData(String spanSummaryKey, long duration) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        // aggregate info is only tracked within an active trace
        if (currentTrace != null) {
            currentTrace.recordSpanDuration(spanSummaryKey, duration);
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

    public interface PluginServicesImplFactory {
        public PluginServicesImpl create(String pluginId);
    }
}
