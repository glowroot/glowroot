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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.ErrorMessage;
import org.informantproject.api.MessageSupplier;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Span;
import org.informantproject.api.Timer;
import org.informantproject.core.PluginServicesImpl.PluginServicesImplFactory;
import org.informantproject.core.config.ConfigService;

/**
 * Plugins may get instantiated by aspectj and request their PluginServices before Informant has
 * finished starting up, in which case they are given this proxy which will point to the real
 * PluginServices as soon as possible.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class PluginServicesProxy extends PluginServices {

    private final String pluginId;
    private final MetricCache metricCache;

    private final List<ConfigListener> pendingListeners =
            new CopyOnWriteArrayList<ConfigListener>();

    @Nullable
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
    @Nullable
    public String getStringProperty(String propertyName) {
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
    @Nullable
    public Double getDoubleProperty(String propertyName) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getDoubleProperty(propertyName);
        }
    }

    @Override
    public void registerConfigListener(ConfigListener listener) {
        pendingListeners.add(listener);
    }

    @Override
    public Span startTrace(MessageSupplier messageSupplier, Metric metric) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.startTrace(messageSupplier, metric);
        }
    }

    @Override
    public Span startTrace(MessageSupplier messageSupplier, Metric metric,
            @Nullable String userId) {

        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.startTrace(messageSupplier, metric, userId);
        }
    }

    @Override
    public Span startSpan(MessageSupplier messageSupplier, Metric metric) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.startSpan(messageSupplier, metric);
        }
    }

    @Override
    public Timer startTimer(Metric metric) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.startTimer(metric);
        }
    }

    @Override
    public void addSpan(MessageSupplier messageSupplier) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            pluginServices.addSpan(messageSupplier);
        }
    }

    @Override
    public void addErrorSpan(ErrorMessage errorMessage) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            pluginServices.addErrorSpan(errorMessage);
        }
    }

    @Override
    public void setUserId(@Nullable String userId) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            pluginServices.setUserId(userId);
        }
    }

    @Override
    public void setTraceAttribute(String name, @Nullable String value) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            pluginServices.setTraceAttribute(name, value);
        }
    }

    @Override
    @Nullable
    public MessageSupplier getRootMessageSupplier() {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getRootMessageSupplier();
        }
    }

    void start(PluginServicesImplFactory pluginServicesImplFactory, ConfigService configService) {
        this.pluginServices = pluginServicesImplFactory.create(pluginId);
        // not that proxy points to the real PluginServices, register the pending listeners and
        // notify them that config values are available from its (cached) PluginServices
        for (ConfigListener pendingListener : pendingListeners) {
            configService.addConfigListener(pendingListener);
            pendingListener.onChange();
        }
    }
}
