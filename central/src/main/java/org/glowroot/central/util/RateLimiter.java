/*
 * Copyright 2016 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import static java.util.concurrent.TimeUnit.DAYS;

public class RateLimiter<T extends /*@NonNull*/ Object> {

    private final Cache<T, Boolean> acquiredRecently;

    public RateLimiter() {
        this(null);
    }

    public RateLimiter(@Nullable Integer maximumSize) {
        CacheBuilder<Object, Object> cache = CacheBuilder.newBuilder()
                .expireAfterWrite(1, DAYS);
        if (maximumSize != null) {
            cache.maximumSize(maximumSize);
        }
        acquiredRecently = cache.build();
    }

    public boolean tryAcquire(T key) {
        synchronized (acquiredRecently) {
            if (acquiredRecently.getIfPresent(key) != null) {
                return false;
            }
            acquiredRecently.put(key, true);
            return true;
        }
    }

    public void invalidate(T key) {
        acquiredRecently.invalidate(key);
    }
}
