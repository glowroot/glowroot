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

import java.util.concurrent.Callable;

import org.informantproject.api.Optional;
import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanDetail;
import org.informantproject.core.trace.PluginServicesImpl.PluginServicesImplFactory;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;

/**
 * Plugins may get instantiated by aspectj and request their PluginServices before Informant has
 * finished starting up, in which case they are given this proxy which will point to the real
 * PluginServices as soon as possible.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class PluginServicesProxy extends PluginServices {

    private final String pluginId;
    private volatile PluginServices pluginServices;

    public PluginServicesProxy(String pluginId) {
        this.pluginId = pluginId;
    }

    @Override
    public boolean isEnabled() {
        if (pluginServices == null) {
            return false;
        } else {
            return pluginServices.isEnabled();
        }
    }

    @Override
    public Optional<String> getStringProperty(String propertyName) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getStringProperty(propertyName);
        }
    }

    @Override
    public boolean getBooleanProperty(String propertyName) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getBooleanProperty(propertyName);
        }
    }

    @Override
    public Optional<Double> getDoubleProperty(String propertyName) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getDoubleProperty(propertyName);
        }
    }

    @Override
    public Object executeRootSpan(RootSpanDetail rootSpanDetail, ProceedingJoinPoint joinPoint,
            String spanSummaryKey) throws Throwable {

        if (pluginServices == null) {
            return joinPoint.proceed();
        } else {
            return pluginServices.executeRootSpan(rootSpanDetail, joinPoint, spanSummaryKey);
        }
    }

    @Override
    public Object executeSpan(SpanDetail spanDetail, ProceedingJoinPoint joinPoint,
            String spanSummaryKey) throws Throwable {

        if (pluginServices == null) {
            return joinPoint.proceed();
        } else {
            return pluginServices.executeSpan(spanDetail, joinPoint, spanSummaryKey);
        }
    }

    @Override
    public Object proceedAndRecordMetricData(ProceedingJoinPoint joinPoint, String spanSummaryKey)
            throws Throwable {

        if (pluginServices == null) {
            return joinPoint.proceed();
        } else {
            return pluginServices.proceedAndRecordMetricData(joinPoint, spanSummaryKey);
        }
    }

    @Override
    public <V> V proceedAndRecordMetricData(Callable<V> callable,
            String spanSummaryKey) throws Exception {

        if (pluginServices == null) {
            return callable.call();
        } else {
            return pluginServices.proceedAndRecordMetricData(callable, spanSummaryKey);
        }
    }

    @Override
    public RootSpanDetail getRootSpanDetail() {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getRootSpanDetail();
        }
    }

    void start(PluginServicesImplFactory pluginServicesImplFactory) {
        this.pluginServices = pluginServicesImplFactory.create(pluginId);
    }
}
