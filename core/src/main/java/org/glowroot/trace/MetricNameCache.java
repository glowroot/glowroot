/*
 * Copyright 2012-2013 the original author or authors.
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

import com.google.common.base.Ticker;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.MetricName;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.model.MetricNameImpl;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class MetricNameCache {

    private static final Logger logger = LoggerFactory.getLogger(MetricNameCache.class);

    private final Ticker ticker;

    private final LoadingCache<String, MetricName> metricNames =
            CacheBuilder.newBuilder().build(new CacheLoader<String, MetricName>() {
                @Override
                public MetricName load(String name) {
                    return new MetricNameImpl(name, ticker);
                }
            });

    MetricNameCache(Ticker ticker) {
        this.ticker = ticker;
    }

    MetricName getMetricName(Class<?> adviceClass) {
        Pointcut pointcut = adviceClass.getAnnotation(Pointcut.class);
        if (pointcut == null) {
            logger.warn("advice has no @Pointcut: {}", adviceClass.getName());
            return getUnknownMetricName();
        } else if (pointcut.metricName().equals("")) {
            logger.warn("advice @Pointcut has no metricName() attribute: {}",
                    adviceClass.getName());
            return getUnknownMetricName();
        } else {
            return getMetricName(pointcut.metricName());
        }
    }

    MetricName getMetricName(String name) {
        return metricNames.getUnchecked(name);
    }

    MetricName getUnknownMetricName() {
        return metricNames.getUnchecked("unknown");
    }
}
