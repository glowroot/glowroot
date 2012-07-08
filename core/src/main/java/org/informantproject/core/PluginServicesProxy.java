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

import org.informantproject.api.Message;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Stopwatch;
import org.informantproject.api.Supplier;
import org.informantproject.api.SupplierOfNullable;
import org.informantproject.core.configuration.ConfigurationService;
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

    private final List<ConfigurationListener> pendingListeners =
            new CopyOnWriteArrayList<ConfigurationListener>();

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
    public void registerConfigurationListener(ConfigurationListener listener) {
        pendingListeners.add(listener);
    }

    @Override
    public Stopwatch startTrace(Supplier<Message> messageSupplier, Metric metric) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.startTrace(messageSupplier, metric);
        }
    }

    @Override
    public Stopwatch startEntry(Supplier<Message> messageSupplier, Metric metric) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.startEntry(messageSupplier, metric);
        }
    }

    @Override
    public void setUsername(SupplierOfNullable<String> username) {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            pluginServices.setUsername(username);
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
    @Nullable
    public Supplier<Message> getRootMessageSupplier() {
        if (pluginServices == null) {
            throw new IllegalStateException("Informant hasn't finished initializing yet."
                    + "  Plugins should check isEnabled() first.");
        } else {
            return pluginServices.getRootMessageSupplier();
        }
    }

    void start(PluginServicesImplFactory pluginServicesImplFactory,
            ConfigurationService configurationService) {

        this.pluginServices = pluginServicesImplFactory.create(pluginId);
        // not that proxy points to the real PluginServices, register the pending listeners and
        // notify them that configuration values are available from its (cached) PluginServices
        for (ConfigurationListener pendingListener : pendingListeners) {
            configurationService.addConfigurationListener(pendingListener);
            pendingListener.onChange();
        }
    }
}
