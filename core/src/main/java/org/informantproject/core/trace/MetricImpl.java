/**
 * Copyright 2012 the original author or authors.
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

import org.informantproject.api.Metric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MetricImpl implements Metric {

    private static final Logger logger = LoggerFactory.getLogger(MetricImpl.class);

    private final String name;
    private final ThreadLocal<MetricDataItem> metricDataItem = new ThreadLocal<MetricDataItem>();

    private final Ticker ticker;

    public MetricImpl(String name, Ticker ticker) {
        this.name = name;
        this.ticker = ticker;
    }

    public String getName() {
        return name;
    }

    boolean start() {
        MetricDataItem item = metricDataItem.get();
        if (item == null) {
            item = new MetricDataItem(name, ticker);
            item.start();
            metricDataItem.set(item);
            return false;
        } else {
            item.start();
            return true;
        }
    }

    boolean start(long startTick) {
        MetricDataItem item = metricDataItem.get();
        if (item == null) {
            item = new MetricDataItem(name, ticker);
            item.start(startTick);
            metricDataItem.set(item);
            return false;
        } else {
            item.start(startTick);
            return true;
        }
    }

    void stop() {
        MetricDataItem item = metricDataItem.get();
        if (item == null) {
            logger.error("stop(): thread local is null");
        } else {
            item.stop();
        }
    }

    void stop(long endTick) {
        MetricDataItem item = metricDataItem.get();
        if (item == null) {
            logger.error("stop(): thread local is null");
        } else {
            item.stop(endTick);
        }
    }

    MetricDataItem get() {
        return metricDataItem.get();
    }

    void remove() {
        metricDataItem.remove();
    }
}
