/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.central.util;

import com.google.common.cache.CacheStats;

public interface LocalCacheStatsMXBean {

    LocalCacheStatsWrapper getStats();

    static class LocalCacheStatsWrapper {

        private final CacheStats cacheStats;

        LocalCacheStatsWrapper(CacheStats cacheStats) {
            this.cacheStats = cacheStats;
        }

        public long getRequestCount() {
            return cacheStats.requestCount();
        }

        public long getHitCount() {
            return cacheStats.hitCount();
        }

        public double getHitRate() {
            return cacheStats.hitRate();
        }

        public long getMissCount() {
            return cacheStats.missCount();
        }

        public double getMissRate() {
            return cacheStats.missRate();
        }

        public long getLoadCount() {
            return cacheStats.loadCount();
        }

        public long getLoadSuccessCount() {
            return cacheStats.loadSuccessCount();
        }

        public long getLoadExceptionCount() {
            return cacheStats.loadExceptionCount();
        }

        public double getLoadExceptionRate() {
            return cacheStats.loadExceptionRate();
        }

        public long getTotalLoadTime() {
            return cacheStats.totalLoadTime();
        }

        public double getAverageLoadPenalty() {
            return cacheStats.averageLoadPenalty();
        }

        public long getEvictionCount() {
            return cacheStats.evictionCount();
        }
    }
}
