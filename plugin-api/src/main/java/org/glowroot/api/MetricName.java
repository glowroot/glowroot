/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.api;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import dataflow.quals.Pure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.Pointcut;

/**
 * See {@link PluginServices#getMetricName(Class)} for how to retrieve and use {@code Metric}
 * instances.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// used to ensure one instance per name so that pointer equality can be used instead of String
// equality
//
// also used to ensure @Pointcut metric name matches metric name passed to PluginServices
public class MetricName {

    private static final Logger logger = LoggerFactory.getLogger(MetricName.class);

    private static final LoadingCache<String, MetricName> metricNames =
            CacheBuilder.newBuilder().build(new CacheLoader<String, MetricName>() {
                @Override
                public MetricName load(String name) {
                    return new MetricName(name);
                }
            });

    /**
     * Returns the {@code Metric} instance for the specified {@code adviceClass}.
     * 
     * {@code adviceClass} must be a {@code Class} with a {@link Pointcut} annotation that has a
     * non-empty {@link Pointcut#metricName()}. This is how the {@code Metric} is named.
     * 
     * The same {@code Metric} is always returned for a given {@code adviceClass}.
     * 
     * This {@code Metric} instance is needed for several of the {@code PluginServices} methods. It
     * is primarily an optimization to pass this {@code Metric} instance instead of a metric name,
     * so that {@code PluginServices} doesn't have to look up its internal metric object on each
     * call.
     * 
     * The return value can (and should) be cached by the plugin for the life of the jvm to avoid
     * looking it up every time it is needed (which is often).
     * 
     * @param adviceClass
     * @return the {@code Metric} instance for the specified {@code adviceClass}
     */
    public static MetricName get(Class<?> adviceClass) {
        if (adviceClass == null) {
            logger.error("get(): argument 'adviceClass' must be non-null");
            return getUnknownMetricName();
        }
        Pointcut pointcut = adviceClass.getAnnotation(Pointcut.class);
        if (pointcut == null) {
            logger.warn("advice has no @Pointcut: {}", adviceClass.getName());
            return getUnknownMetricName();
        } else if (pointcut.metricName().isEmpty()) {
            logger.warn("advice @Pointcut has no metricName() attribute: {}",
                    adviceClass.getName());
            return getUnknownMetricName();
        } else {
            return getMetricName(pointcut.metricName());
        }
    }

    private final String name;

    private MetricName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    private static MetricName getMetricName(String name) {
        return metricNames.getUnchecked(name);
    }

    private static MetricName getUnknownMetricName() {
        return metricNames.getUnchecked("unknown");
    }

    @Override
    @Pure
    public String toString() {
        return name;
    }
}
