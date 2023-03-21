/*
 * Copyright 2016-2023 the original author or authors.
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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import static java.util.concurrent.TimeUnit.DAYS;

public class RateLimiter<T extends /*@NonNull*/ Object> {

    private static final int NO_MAXIMUM_SIZE = -1;

    private final Cache<T, Boolean> acquiredRecently;

    public RateLimiter() {
        this(NO_MAXIMUM_SIZE, false);
    }

    public RateLimiter(int maximumSize, boolean recordStats) {
        CacheBuilder<Object, Object> cache = CacheBuilder.newBuilder()
                .expireAfterWrite(1, DAYS);
        if (maximumSize != NO_MAXIMUM_SIZE) {
            cache.maximumSize(maximumSize);
        }
        if (recordStats) {
            cache.recordStats();
        }
        acquiredRecently = cache.build();
    }

    public boolean tryAcquire(T key) {
        // don't use asMap().putIfAbsent() because it doesn't update the guava cache stats
        Boolean val = acquiredRecently.getIfPresent(key);
        if (val == null) {
            acquiredRecently.put(key, true);
            return true;
        } else {
            return false;
        }
    }

    public void release(T key) {
        acquiredRecently.invalidate(key);
    }

    public LocalCacheStats getLocalCacheStats() {
        return new LocalCacheStats(acquiredRecently);
    }
}
