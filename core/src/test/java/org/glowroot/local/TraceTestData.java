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
package org.glowroot.local;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

import org.glowroot.collector.spi.GarbageCollectionActivity;
import org.glowroot.collector.spi.Trace;
import org.glowroot.collector.spi.TraceEntry;
import org.glowroot.collector.spi.TraceTimerNode;
import org.glowroot.common.util.Styles;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Styles.Private
@Value.Include({Trace.class, TraceTimerNode.class})
class TraceTestData {

    private static final AtomicInteger counter = new AtomicInteger();

    static Trace createTrace() {
        return new TraceBuilder()
                .id("abc" + counter.getAndIncrement())
                .partial(false)
                .slow(true)
                .error(false)
                .startTime(1)
                .captureTime(11)
                .durationNanos(MILLISECONDS.toNanos(10))
                .transactionType("unit test")
                .transactionName("test transaction name")
                .headline("test headline")
                .user("j")
                .customAttributes(ImmutableMap.<String, Collection<String>>of("abc",
                        ImmutableList.of("xyz"), "xyz", ImmutableList.of("abc")))
                .customDetail(ImmutableMap.of("abc1", "xyz1", "xyz2", "abc2"))
                .rootTimer(new TraceTimerNodeBuilder()
                        .name("the top")
                        .extended(false)
                        .totalNanos(123)
                        .count(1)
                        .active(false)
                        .childNodes(ImmutableList.<TraceTimerNode>of())
                        .build())
                .threadCpuNanos(-1)
                .threadBlockedNanos(-1)
                .threadWaitedNanos(-1)
                .threadAllocatedBytes(-1)
                .gcActivity(ImmutableMap.<String, GarbageCollectionActivity>of())
                .entries(ImmutableList.<TraceEntry>of())
                .entryLimitExceeded(false)
                .profileLimitExceeded(false)
                .build();
    }
}
