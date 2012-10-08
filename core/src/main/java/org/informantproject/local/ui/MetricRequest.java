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
package org.informantproject.local.ui;

import java.util.List;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

/**
 * Structure used to deserialize request parameters sent to "/metrics".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class MetricRequest {

    private List<String> metricIds = ImmutableList.of();
    private long start;
    private long end;

    public List<String> getMetricIds() {
        return metricIds;
    }

    public void setMetricIds(List<String> metricIds) {
        this.metricIds = metricIds;
    }

    public long getStart() {
        return start;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public long getEnd() {
        return end;
    }

    public void setEnd(long end) {
        this.end = end;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("metricIds", metricIds)
                .add("start", start)
                .add("end", end)
                .toString();
    }
}
