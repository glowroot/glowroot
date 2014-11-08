/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.transaction.model;

import org.checkerframework.checker.nullness.qual.Nullable;

// micro-optimized map for nested metrics
class NestedMetricMap {

    // table length must always be a power of 2, see comment in get()
    private final Entry[] table = new Entry[16];

    @Nullable
    TransactionMetricImpl get(MetricNameImpl metricName) {
        // this mask requires table length to be a power of 2
        int bucket = metricName.getSpecialHashCode() & (table.length - 1);
        Entry entry = table[bucket];
        while (true) {
            if (entry == null) {
                return null;
            }
            if (entry.metricName == metricName) {
                return entry.transactionMetric;
            }
            entry = entry.nextEntry;
        }
    }

    void put(MetricNameImpl metricName, TransactionMetricImpl transactionMetric) {
        Entry newEntry = new Entry(metricName, transactionMetric);
        int bucket = metricName.getSpecialHashCode() & (table.length - 1);
        Entry entry = table[bucket];
        if (entry == null) {
            table[bucket] = newEntry;
            return;
        }
        Entry nextEntry;
        while ((nextEntry = entry.nextEntry) != null) {
            entry = nextEntry;
        }
        entry.nextEntry = newEntry;
    }

    private static class Entry {

        private final MetricNameImpl metricName;
        private final TransactionMetricImpl transactionMetric;
        @Nullable
        private Entry nextEntry;

        private Entry(MetricNameImpl metricName, TransactionMetricImpl transactionMetric) {
            this.metricName = metricName;
            this.transactionMetric = transactionMetric;
        }
    }
}
