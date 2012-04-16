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
package org.informantproject.api;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * This is the primary service exposed to plugins.
 * 
 * It is safe for plugins to cache the return value of PluginServices.get().
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO write javadocs for all public methods
public abstract class PluginServices {

    private static final Logger logger = LoggerFactory.getLogger(PluginServices.class);

    private static final String MAIN_ENTRY_POINT_CLASS_NAME =
            "org.informantproject.core.MainEntryPoint";
    private static final String CREATE_PLUGIN_SERVICES_METHOD_NAME = "createPluginServices";

    private static final LoadingCache<String, PluginServices> pluginServices = CacheBuilder
            .newBuilder().build(new CacheLoader<String, PluginServices>() {
                @Override
                public PluginServices load(String pluginId) throws Exception {
                    return createPluginServices(pluginId);
                }
            });

    public abstract Metric getMetric(Class<?> adviceClass);

    public abstract void registerConfigurationListener(ConfigurationListener listener);

    public abstract boolean isEnabled();

    public abstract Optional<String> getStringProperty(String propertyName);

    public abstract boolean getBooleanProperty(String propertyName);

    public abstract Optional<Double> getDoubleProperty(String propertyName);

    public abstract Span startRootSpan(Metric metric, RootSpanDetail rootSpanDetail);

    public abstract Span startSpan(Metric metric, SpanDetail spanDetail);

    public abstract void endSpan(Span span);

    public abstract TraceMetric startMetric(Metric metric);

    public abstract void endMetric(TraceMetric traceMetric);

    public abstract void putTraceAttribute(String name, String value);

    public abstract RootSpanDetail getRootSpanDetail();

    public static PluginServices get(String pluginId) {
        return pluginServices.getUnchecked(pluginId);
    }

    private static PluginServices createPluginServices(String pluginId) {
        try {
            Class<?> mainEntryPointClass = Class.forName(MAIN_ENTRY_POINT_CLASS_NAME);
            Method getPluginServicesMethod = mainEntryPointClass
                    .getMethod(CREATE_PLUGIN_SERVICES_METHOD_NAME, String.class);
            return (PluginServices) getPluginServicesMethod.invoke(null, pluginId);
        } catch (ClassNotFoundException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new NopPluginServices();
        } catch (SecurityException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new NopPluginServices();
        } catch (NoSuchMethodException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new NopPluginServices();
        } catch (IllegalArgumentException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new NopPluginServices();
        } catch (IllegalAccessException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new NopPluginServices();
        } catch (InvocationTargetException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new NopPluginServices();
        }
    }

    public interface ConfigurationListener {
        // the new configuration is not passed to onChange so that the receiver has to get the
        // latest which avoids race condition worries that two updates may get sent to the receiver
        // in the wrong order
        void onChange();
    }

    private static class NopPluginServices extends PluginServices {
        @Override
        public Metric getMetric(Class<?> adviceClass) {
            return null;
        }
        @Override
        public boolean isEnabled() {
            return false;
        }
        @Override
        public Optional<String> getStringProperty(String propertyName) {
            return Optional.absent();
        }
        @Override
        public boolean getBooleanProperty(String propertyName) {
            return false;
        }
        @Override
        public Optional<Double> getDoubleProperty(String propertyName) {
            return Optional.absent();
        }
        @Override
        public void registerConfigurationListener(ConfigurationListener listener) {}
        @Override
        public Span startRootSpan(Metric metric, RootSpanDetail rootSpanDetail) {
            return null;
        }
        @Override
        public Span startSpan(Metric metric, SpanDetail spanDetail) {
            return null;
        }
        @Override
        public void endSpan(Span span) {}
        @Override
        public TraceMetric startMetric(Metric metric) {
            return null;
        }
        @Override
        public void endMetric(TraceMetric traceMetric) {}
        @Override
        public void putTraceAttribute(String name, String value) {}
        @Override
        public RootSpanDetail getRootSpanDetail() {
            return null;
        }
    }
}
