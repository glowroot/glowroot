/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject;

import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanDetail;
import org.informantproject.configuration.ConfigurationService;
import org.informantproject.configuration.ImmutableCoreConfiguration;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.informantproject.trace.Span;
import org.informantproject.trace.Trace;
import org.informantproject.trace.TraceService;
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
class PluginServicesImpl extends PluginServices {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TraceService traceService;
    private final ConfigurationService configurationService;
    private final Ticker ticker;

    @Inject
    PluginServicesImpl(TraceService traceService, ConfigurationService configurationService,
            Ticker ticker) {

        this.traceService = traceService;
        this.configurationService = configurationService;
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
        if (traceService.isCurrentRootSpanDisabled()) {
            // tracing was enabled after the current trace had started
            return proceedAndRecordMetricData(joinPoint, spanSummaryKey);
        }
        Trace currentTrace = traceService.getCurrentTrace();
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
            traceService.recordSummaryData(spanSummaryKey, endTime - startTime);
        }
    }

    @Override
    public RootSpanDetail getRootSpanDetail() {
        Trace trace = traceService.getCurrentTrace();
        if (trace == null) {
            return null;
        } else {
            return (RootSpanDetail) trace.getRootSpan().getRootSpan().getSpanDetail();
        }
    }

    @Override
    public boolean isEnabled() {
        boolean enabled = configurationService.getCoreConfiguration().isEnabled()
                && !traceService.isCurrentRootSpanDisabled();
        logger.debug("isEnabled(): enabled={}", enabled);
        return enabled;
    }

    private Object proceedAndRecordSpanAndMetricData(SpanDetail spanDetail,
            ProceedingJoinPoint joinPoint, String spanSummaryKey) throws Throwable {

        // start span
        Span span = traceService.pushSpan(spanDetail);
        try {
            return joinPoint.proceed();
        } finally {
            // minimizing the number of calls to the clock timer as they are relatively expensive
            long endTime = ticker.read();
            // record aggregate timing data
            if (spanSummaryKey != null) {
                traceService.recordSummaryData(spanSummaryKey, endTime - span.getStartTime());
            }
            // pop span needs to be the last step (at least when this is a root span)
            traceService.popSpan(span, endTime);
        }
    }

    private boolean isInTrace() {
        return traceService.getCurrentTrace() != null;
    }

    private Object proceedAndDisableNested(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean previouslyDisabled = traceService.isCurrentRootSpanDisabled();
        try {
            // disable current trace so that nested spans will not be captured even
            // if tracing is re-enabled mid-trace
            traceService.setCurrentRootSpanDisabled(true);
            return joinPoint.proceed();
        } finally {
            traceService.setCurrentRootSpanDisabled(previouslyDisabled);
        }
    }
}
