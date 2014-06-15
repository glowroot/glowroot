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
package org.glowroot.trace;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.TraceMetricName;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.trace.model.TraceMetricNameImpl;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// used to ensure one instance per name so that pointer equality can be used instead of String
// equality
//
// also used to ensure @Pointcut trace metric name matches trace metric name passed to
// PluginServices
class TraceMetricNameCache {

    private static final Logger logger = LoggerFactory.getLogger(TraceMetricNameCache.class);

    private final LoadingCache<String, TraceMetricNameImpl> names =
            CacheBuilder.newBuilder().build(new CacheLoader<String, TraceMetricNameImpl>() {
                @Override
                public TraceMetricNameImpl load(String name) {
                    return new TraceMetricNameImpl(name);
                }
            });

    TraceMetricName getName(Class<?> adviceClass) {
        if (adviceClass == null) {
            logger.error("get(): argument 'adviceClass' must be non-null");
            return getUnknownName();
        }
        Pointcut pointcut = adviceClass.getAnnotation(Pointcut.class);
        if (pointcut == null) {
            logger.warn("advice has no @Pointcut: {}", adviceClass.getName());
            return getUnknownName();
        } else if (pointcut.traceMetric().isEmpty()) {
            logger.warn("advice @Pointcut has no traceMetric() attribute: {}",
                    adviceClass.getName());
            return getUnknownName();
        } else {
            return getName(pointcut.traceMetric());
        }
    }

    private TraceMetricName getName(String name) {
        return names.getUnchecked(name);
    }

    private TraceMetricName getUnknownName() {
        return names.getUnchecked("unknown");
    }
}
