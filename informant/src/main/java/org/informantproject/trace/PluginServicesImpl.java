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
package org.informantproject.trace;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;

import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanDetail;
import org.informantproject.configuration.ConfigurationService;
import org.informantproject.configuration.ImmutableCoreConfiguration;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.informantproject.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of PluginServices from the Plugin API.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class PluginServicesImpl extends PluginServices {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TraceRegistry traceRegistry;
    private final TraceSink traceSink;
    private final ConfigurationService configurationService;
    private final Clock clock;
    private final Ticker ticker;

    @Inject
    PluginServicesImpl(TraceRegistry traceRegistry, TraceSink traceSink,
            ConfigurationService configurationService, Clock clock, Ticker ticker) {

        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.configurationService = configurationService;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Override
    public String getStringProperty(String pluginName, String propertyName) {
        logger.debug("getStringProperty(): pluginName={}, propertyName={}", pluginName,
                propertyName);
        return configurationService.getPluginConfiguration().getStringProperty(pluginName,
                propertyName);
    }

    @Override
    public Boolean getBooleanProperty(String pluginName, String propertyName,
            Boolean defaultValue) {

        logger.debug("getBooleanProperty(): pluginName={}, propertyName={}", pluginName,
                propertyName);
        return configurationService.getPluginConfiguration().getBooleanProperty(pluginName,
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

        logger.debug("recordMetricData(): summaryKey={}", spanSummaryKey);
        long startTime = ticker.read();
        try {
            return joinPoint.proceed();
        } finally {
            long endTime = ticker.read();
            // record aggregate timing data
            recordSummaryData(spanSummaryKey, endTime - startTime);
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
        try {
            return joinPoint.proceed();
        } finally {
            // minimizing the number of calls to the clock timer as they are relatively expensive
            long endTime = ticker.read();
            // record aggregate timing data
            if (spanSummaryKey != null) {
                recordSummaryData(spanSummaryKey, endTime - span.getStartTime());
            }
            // pop span needs to be the last step (at least when this is a root span)
            popSpan(span, endTime);
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
    private void popSpan(Span span, long elementEndTime) {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        currentTrace.getRootSpan().popSpan(span, elementEndTime);
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
                logger.error(e.getMessage(), e);
            }
        }
    }
}
