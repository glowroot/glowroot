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

import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanDetail;
import org.informantproject.core.trace.PluginServicesImpl;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class PluginServicesProxy extends PluginServices {

    private volatile PluginServices pluginServices;

    @Override
    public boolean isEnabled() {
        if (pluginServices == null) {
            return false;
        } else {
            return pluginServices.isEnabled();
        }
    }

    @Override
    public String getStringProperty(String pluginName, String propertyName) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getStringProperty(pluginName, propertyName);
        }
    }

    @Override
    public Boolean getBooleanProperty(String pluginName, String propertyName,
            Boolean defaultValue) {

        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getBooleanProperty(pluginName, propertyName, defaultValue);
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
    public RootSpanDetail getRootSpanDetail() {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getRootSpanDetail();
        }
    }

    void start(PluginServicesImpl pluginServicesImpl) {
        this.pluginServices = pluginServicesImpl;
    }

    void shutdown() {
        pluginServices = null;
    }
}
