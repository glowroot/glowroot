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

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@ThreadSafe
public abstract class PluginServices {

    private static final Logger logger = LoggerFactory.getLogger(PluginServices.class);

    private static final String MAIN_ENTRY_POINT_CLASS_NAME =
            "org.informantproject.core.MainEntryPoint";
    private static final String CREATE_PLUGIN_SERVICES_METHOD_NAME = "createPluginServices";

    private static final LoadingCache<String, PluginServices> pluginServices = CacheBuilder
            .newBuilder().build(new CacheLoader<String, PluginServices>() {
                @Override
                public PluginServices load(String pluginId) {
                    return createPluginServices(pluginId);
                }
            });

    public static PluginServices get(String pluginId) {
        return pluginServices.getUnchecked(pluginId);
    }

    protected PluginServices() {}

    public abstract Metric getMetric(Class<?> adviceClass);

    public abstract void registerConfigListener(ConfigListener listener);

    public abstract boolean isEnabled();

    @Nullable
    public abstract String getStringProperty(String propertyName);

    public abstract boolean getBooleanProperty(String propertyName);

    @Nullable
    public abstract Double getDoubleProperty(String propertyName);

    // if there is no trace already bound to the current thread, a new trace is created and bound.
    // if there is already a trace bound to the current thread, this method delegates to startSpan()
    public abstract Span startTrace(MessageSupplier messageSupplier, Metric metric);

    public abstract Span startSpan(MessageSupplier messageSupplier, Metric metric);

    public abstract Timer startTimer(Metric metric);

    public abstract void addSpan(MessageSupplier messageSupplier);

    // does not mark trace as error
    public abstract void addErrorSpan(ErrorMessage errorMessage);

    public abstract void setUserId(@Nullable String userId);

    // sets trace attribute in
    public abstract void setTraceAttribute(String name, @Nullable String value);

    @Nullable
    public abstract MessageSupplier getRootMessageSupplier();

    private static PluginServices createPluginServices(String pluginId) {
        try {
            Class<?> mainEntryPointClass = Class.forName(MAIN_ENTRY_POINT_CLASS_NAME);
            Method getPluginServicesMethod = mainEntryPointClass.getMethod(
                    CREATE_PLUGIN_SERVICES_METHOD_NAME, String.class);
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

    public interface ConfigListener {
        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange();
    }

    private static class NopPluginServices extends PluginServices {
        @Override
        public Metric getMetric(Class<?> adviceClass) {
            return NopMetric.INSTANCE;
        }
        @Override
        public boolean isEnabled() {
            return false;
        }
        @Override
        @Nullable
        public String getStringProperty(String propertyName) {
            return null;
        }
        @Override
        public boolean getBooleanProperty(String propertyName) {
            return false;
        }
        @Override
        @Nullable
        public Double getDoubleProperty(String propertyName) {
            return null;
        }
        @Override
        public void registerConfigListener(ConfigListener listener) {}
        @Override
        public Span startTrace(MessageSupplier messageSupplier, Metric metric) {
            return NopSpan.INSTANCE;
        }
        @Override
        public Span startSpan(MessageSupplier messageSupplier, Metric metric) {
            return NopSpan.INSTANCE;
        }
        @Override
        public Timer startTimer(Metric metric) {
            return NopTimer.INSTANCE;
        }
        @Override
        public void addSpan(MessageSupplier messageSupplier) {}
        @Override
        public void addErrorSpan(ErrorMessage errorMessage) {}
        @Override
        public void setUserId(@Nullable String userId) {}
        @Override
        public void setTraceAttribute(String name, @Nullable String value) {}
        @Override
        @Nullable
        public MessageSupplier getRootMessageSupplier() {
            return null;
        }

        @ThreadSafe
        private static class NopMetric implements Metric {
            private static final NopMetric INSTANCE = new NopMetric();
            public String getName() {
                return "NopMetric";
            }
        }

        @ThreadSafe
        private static class NopSpan implements Span {
            private static final NopSpan INSTANCE = new NopSpan();
            public void end() {}
            public void endWithError(ErrorMessage errorMessage) {}
            public void updateMessage(MessageUpdater updater) {}
        }

        @ThreadSafe
        private static class NopTimer implements Timer {
            private static final NopTimer INSTANCE = new NopTimer();
            public void end() {}
        }
    }
}
