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

import org.informantproject.api.Metric;
import org.informantproject.api.Optional;
import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.Span;
import org.informantproject.api.SpanDetail;
import org.informantproject.api.TraceMetric;
import org.informantproject.core.metric.MetricCache;
import org.informantproject.core.trace.PluginServicesImpl.PluginServicesImplFactory;

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
    private final MetricCache metricCache;

    private volatile PluginServices pluginServices;

    public PluginServicesProxy(String pluginId, MetricCache metricCache) {
        this.pluginId = pluginId;
        this.metricCache = metricCache;
    }

    @Override
    public Metric getMetric(Class<?> adviceClass) {
        return metricCache.getMetric(adviceClass);
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
    public Span startRootSpan(Metric metric, RootSpanDetail rootSpanDetail) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.startRootSpan(metric, rootSpanDetail);
        }
    }

    @Override
    public Span startSpan(Metric metric, SpanDetail spanDetail) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.startSpan(metric, spanDetail);
        }
    }

    @Override
    public void endSpan(Span span) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            pluginServices.endSpan(span);
        }
    }

    @Override
    public TraceMetric startMetric(Metric metric) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.startMetric(metric);
        }
    }

    @Override
    public void endMetric(TraceMetric traceMetric) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            pluginServices.endMetric(traceMetric);
        }
    }

    @Override
    public void putTraceAttribute(String name, String value) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            pluginServices.putTraceAttribute(name, value);
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
