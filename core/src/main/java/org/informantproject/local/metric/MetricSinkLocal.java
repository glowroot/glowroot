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
package org.informantproject.local.metric;

import java.util.List;

import org.informantproject.core.metric.MetricSink;
import org.informantproject.core.metric.MetricValue;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of MetricSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class MetricSinkLocal implements MetricSink {

    private final MetricDao metricDao;

    @Inject
    MetricSinkLocal(MetricDao metricDao) {
        this.metricDao = metricDao;
    }

    public void onMetricCapture(List<MetricValue> metricValues) {
        metricDao.storeMetricValues(metricValues);
    }
}
