/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.api;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import checkers.nullness.quals.Nullable;

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

    private static final String MAIN_ENTRY_POINT_CLASS_NAME = "io.informant.core.MainEntryPoint";
    private static final String GET_PLUGIN_SERVICES_METHOD_NAME = "getPluginServices";

    public static PluginServices get(String pluginId) {
        return getPluginServices(pluginId);
    }

    protected PluginServices() {}

    public abstract Metric getMetric(Class<?> adviceClass);

    public abstract void registerConfigListener(ConfigListener listener);

    public abstract boolean isEnabled();

    public abstract String getStringProperty(String propertyName);

    public abstract boolean getBooleanProperty(String propertyName);

    @Nullable
    public abstract Double getDoubleProperty(String propertyName);

    public abstract Span startTrace(MessageSupplier messageSupplier, Metric metric);

    // if there is no trace already bound to the current thread, a new background trace is created
    // and bound.
    // if there is already a trace bound to the current thread, this method delegates to startSpan()
    public abstract Span startBackgroundTrace(MessageSupplier messageSupplier, Metric metric);

    public abstract Span startSpan(MessageSupplier messageSupplier, Metric metric);

    public abstract MetricTimer startMetricTimer(Metric metric);

    public abstract void addSpan(MessageSupplier messageSupplier);

    // does not mark trace as error
    public abstract void addErrorSpan(ErrorMessage errorMessage);

    public abstract void setUserId(@Nullable String userId);

    // sets trace attribute in
    public abstract void setTraceAttribute(String name, @Nullable String value);

    private static PluginServices getPluginServices(String pluginId) {
        try {
            Class<?> mainEntryPointClass = Class.forName(MAIN_ENTRY_POINT_CLASS_NAME);
            Method getPluginServicesMethod = mainEntryPointClass.getMethod(
                    GET_PLUGIN_SERVICES_METHOD_NAME, String.class);
            PluginServices pluginServices = (PluginServices) getPluginServicesMethod.invoke(null,
                    pluginId);
            if (pluginServices == null) {
                // this really really really shouldn't happen
                logger.error(MAIN_ENTRY_POINT_CLASS_NAME + "." + GET_PLUGIN_SERVICES_METHOD_NAME
                        + "(" + pluginId + ") returned null");
                return new PluginServicesNop();
            }
            return pluginServices;
        } catch (ClassNotFoundException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (SecurityException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (NoSuchMethodException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (IllegalArgumentException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (IllegalAccessException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (InvocationTargetException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        }
    }

    public interface ConfigListener {
        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange();
    }

    private static class PluginServicesNop extends PluginServices {
        @Override
        public Metric getMetric(Class<?> adviceClass) {
            return NopMetric.INSTANCE;
        }
        @Override
        public boolean isEnabled() {
            return false;
        }
        @Override
        public String getStringProperty(String propertyName) {
            return "";
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
            return new NopSpan(messageSupplier);
        }
        @Override
        public Span startBackgroundTrace(MessageSupplier messageSupplier, Metric metric) {
            return new NopSpan(messageSupplier);
        }
        @Override
        public Span startSpan(MessageSupplier messageSupplier, Metric metric) {
            return new NopSpan(messageSupplier);
        }
        @Override
        public MetricTimer startMetricTimer(Metric metric) {
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

        private static class NopMetric implements Metric {
            private static final NopMetric INSTANCE = new NopMetric();
            public String getName() {
                return "NopMetric";
            }
        }

        private static class NopSpan implements Span {
            private final MessageSupplier messageSupplier;
            private NopSpan(MessageSupplier messageSupplier) {
                this.messageSupplier = messageSupplier;
            }
            public void end() {}
            public void endWithStackTrace(long threshold, TimeUnit unit) {}
            public void endWithError(ErrorMessage errorMessage) {}
            public MessageSupplier getMessageSupplier() {
                return messageSupplier;
            }
        }

        private static class NopTimer implements MetricTimer {
            private static final NopTimer INSTANCE = new NopTimer();
            public void end() {}
        }
    }
}
