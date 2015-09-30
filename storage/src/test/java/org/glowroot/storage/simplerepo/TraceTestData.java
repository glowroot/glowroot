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
package org.glowroot.storage.simplerepo;

import java.util.concurrent.atomic.AtomicInteger;

import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Styles.Private
class TraceTestData {

    private static final AtomicInteger counter = new AtomicInteger();

    static Trace createTrace() {
        Trace.Header header = Trace.Header.newBuilder()
                .setId("abc" + counter.getAndIncrement())
                .setPartial(false)
                .setSlow(true)
                .setStartTime(1)
                .setCaptureTime(11)
                .setDurationNanos(MILLISECONDS.toNanos(10))
                .setTransactionType("unit test")
                .setTransactionName("test transaction name")
                .setHeadline("test headline")
                .setUser("j")
                .addAttribute(Trace.Attribute.newBuilder()
                        .setName("abc")
                        .addValue("xyz"))
                .addAttribute(Trace.Attribute.newBuilder()
                        .setName("xyz")
                        .addValue("abc"))
                .addDetailEntry(Trace.DetailEntry.newBuilder()
                        .setName("abc1")
                        .addValue(Trace.DetailValue.newBuilder().setSval("xyz1").build()))
                .addDetailEntry(Trace.DetailEntry.newBuilder()
                        .setName("xyz2")
                        .addValue(Trace.DetailValue.newBuilder().setSval("abc2").build()))
                .setRootTimer(Trace.Timer.newBuilder()
                        .setName("the top")
                        .setExtended(false)
                        .setTotalNanos(123)
                        .setCount(1)
                        .setActive(false))
                .setThreadCpuNanos(-1)
                .setThreadBlockedNanos(-1)
                .setThreadWaitedNanos(-1)
                .setThreadAllocatedBytes(-1)
                .setEntryLimitExceeded(false)
                .setProfileSampleLimitExceeded(false)
                .build();
        return Trace.newBuilder()
                .setHeader(header)
                .build();
    }
}
