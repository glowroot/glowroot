/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.local.metrics;

import java.util.List;

import org.informantproject.metric.MetricRepository;
import org.informantproject.metric.MetricValue;

import com.google.inject.Inject;

/**
 * Implementation of MetricRepository for local storage in embedded H2 database. Some day there may
 * be another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LocalMetricRepository implements MetricRepository {

    private final MetricPointDao metricPointDao;

    @Inject
    public LocalMetricRepository(MetricPointDao metricPointDao) {
        this.metricPointDao = metricPointDao;
    }

    public void store(List<MetricValue> metricValues) {
        metricPointDao.storeMetricValues(metricValues);
    }
}
