/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.local.store;

import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableSetMultimap;

import org.glowroot.collector.Existence;
import org.glowroot.collector.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class TraceTestData {

    private static final AtomicInteger counter = new AtomicInteger();

    static Trace createTrace() {
        return Trace.builder()
                .id("abc" + counter.getAndIncrement())
                .active(false)
                .partial(false)
                .error(false)
                .startTime(1)
                .captureTime(11)
                .duration(MILLISECONDS.toNanos(10))
                .transactionType("unit test")
                .transactionName("test transaction name")
                .headline("test headline")
                .user("j")
                .customAttributes("{\"abc\":\"xyz\", \"xyz\":\"abc\"}")
                .customAttributesForIndexing(ImmutableSetMultimap.of("abc", "xyz", "xyz", "abc"))
                .customDetail("{\"abc1\":\"xyz1\", \"xyz2\":\"abc2\"}")
                .entryCount(0)
                .entriesExistence(Existence.NO)
                .profileSampleCount(0)
                .profileExistence(Existence.NO)
                .build();
    }
}
