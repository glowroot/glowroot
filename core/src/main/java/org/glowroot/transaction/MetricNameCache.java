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
package org.glowroot.transaction;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.MetricName;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.transaction.model.MetricNameImpl;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// used to ensure one instance per name so that pointer equality can be used instead of String
// equality
//
// also used to ensure @Pointcut metric name matches the metric name passed to PluginServices
class MetricNameCache {

    private static final Logger logger = LoggerFactory.getLogger(MetricNameCache.class);

    private final LoadingCache<String, MetricNameImpl> names =
            CacheBuilder.newBuilder().build(new CacheLoader<String, MetricNameImpl>() {
                @Override
                public MetricNameImpl load(String name) {
                    return new MetricNameImpl(name);
                }
            });

    MetricName getName(Class<?> adviceClass) {
        if (adviceClass == null) {
            logger.error("get(): argument 'adviceClass' must be non-null");
            return getUnknownName();
        }
        Pointcut pointcut = adviceClass.getAnnotation(Pointcut.class);
        if (pointcut == null) {
            logger.warn("advice has no @Pointcut: {}", adviceClass.getName());
            return getUnknownName();
        } else if (pointcut.metricName().isEmpty()) {
            logger.warn("advice @Pointcut has no metricName() attribute: {}",
                    adviceClass.getName());
            return getUnknownName();
        } else {
            return getName(pointcut.metricName());
        }
    }

    private MetricName getName(String name) {
        return names.getUnchecked(name);
    }

    private MetricName getUnknownName() {
        return names.getUnchecked("unknown");
    }
}
