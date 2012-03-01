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
package org.informantproject.core.trace;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Used to track summary data for a given trace (e.g. total jdbc execution time for a given trace)
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MetricData {

    // store timing info per aggregation key
    private final ConcurrentMap<String, MetricDataItem> map =
            new ConcurrentHashMap<String, MetricDataItem>();

    public Iterable<MetricDataItem> getItems() {
        return map.values();
    }

    void recordData(String name, long timeInNanoseconds) {
        MetricDataItem summaryDataItem = map.get(name);
        if (summaryDataItem == null) {
            // it's possible that two threads both instantiate a new TraceSummaryDataItem
            // but only one of the SummaryDataItems will get set via putIfAbsent which is why
            // the value is retrieved afterwards
            map.putIfAbsent(name, new MetricDataItem(name));
            summaryDataItem = map.get(name);
        }
        summaryDataItem.recordData(timeInNanoseconds);
    }
}
