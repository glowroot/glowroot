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
import java.util.concurrent.Callable;

import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;

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

    public abstract boolean isEnabled();

    public abstract Optional<String> getStringProperty(String propertyName);

    public abstract boolean getBooleanProperty(String propertyName);

    public abstract Optional<Double> getDoubleProperty(String propertyName);

    public abstract Object executeRootSpan(String spanName,
            RootSpanDetail rootSpanDetail, ProceedingJoinPoint joinPoint) throws Throwable;

    public abstract Object executeSpan(String spanName, SpanDetail spanDetail,
            ProceedingJoinPoint joinPoint) throws Throwable;

    public abstract Object proceedAndRecordMetricData(String spanName,
            ProceedingJoinPoint joinPoint) throws Throwable;

    public abstract <V> V proceedAndRecordMetricData(String spanName,
            Callable<V> callable) throws Exception;

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

    private static class NopPluginServices extends PluginServices {
        @Override
        public boolean isEnabled() {
            return false;
        }
        @Override
        public Optional<String> getStringProperty(String propertyName) {
            return null;
        }
        @Override
        public boolean getBooleanProperty(String propertyName) {
            return false;
        }
        @Override
        public Optional<Double> getDoubleProperty(String propertyName) {
            return null;
        }
        @Override
        public Object executeRootSpan(String spanName, RootSpanDetail rootSpanDetail,
                ProceedingJoinPoint joinPoint) throws Throwable {
            return joinPoint.proceed();
        }
        @Override
        public Object executeSpan(String spanName, SpanDetail spanDetail,
                ProceedingJoinPoint joinPoint) throws Throwable {
            return joinPoint.proceed();
        }
        @Override
        public Object proceedAndRecordMetricData(String spanName,
                ProceedingJoinPoint joinPoint) throws Throwable {
            return joinPoint.proceed();
        }
        @Override
        public <V> V proceedAndRecordMetricData(String spanName,
                Callable<V> callable) throws Exception {
            return callable.call();
        }
        @Override
        public void putTraceAttribute(String name, String value) {}
        @Override
        public RootSpanDetail getRootSpanDetail() {
            return null;
        }
    }
}
