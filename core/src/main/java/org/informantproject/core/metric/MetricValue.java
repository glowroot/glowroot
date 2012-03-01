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
package org.informantproject.core.metric;

import com.google.common.base.Objects;

/**
 * This will probably become part of the Plugin API.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MetricValue {

    private String metricId;
    private double value;

    public MetricValue() {}

    public MetricValue(String metricId, double value) {
        this.metricId = metricId;
        this.value = value;
    }

    public String getMetricId() {
        return metricId;
    }

    public void setMetricId(String metricId) {
        this.metricId = metricId;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MetricValue)) {
            return false;
        }
        MetricValue other = (MetricValue) o;
        return Objects.equal(metricId, other.getMetricId())
                && Objects.equal(value, other.getValue());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(metricId, value);
    }
}
