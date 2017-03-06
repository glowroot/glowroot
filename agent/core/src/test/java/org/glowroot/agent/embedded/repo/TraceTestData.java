/*
 * Copyright 2011-2017 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.util.UUID;

import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.agent.collector.Collector.TraceVisitor;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Styles.Private
class TraceTestData {

    static TraceReader createTraceReader() {
        return new TraceReaderImpl(createTraceHeader());
    }

    static TraceReader createTraceReader(Trace.Header header) {
        return new TraceReaderImpl(header);
    }

    static Trace.Header createTraceHeader() {
        return Trace.Header.newBuilder()
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
                        .addValue(Trace.DetailValue.newBuilder().setString("xyz1").build()))
                .addDetailEntry(Trace.DetailEntry.newBuilder()
                        .setName("xyz2")
                        .addValue(Trace.DetailValue.newBuilder().setString("abc2").build()))
                .setMainThreadRootTimer(Trace.Timer.newBuilder()
                        .setName("the top")
                        .setExtended(false)
                        .setTotalNanos(123)
                        .setCount(1))
                .build();
    }

    private static class TraceReaderImpl implements TraceReader {

        private final String traceId;
        private final Trace.Header header;

        private TraceReaderImpl(Trace.Header header) {
            this.header = header;
            traceId = UUID.randomUUID().toString();
        }

        @Override
        public long captureTime() {
            return header.getCaptureTime();
        }

        @Override
        public String traceId() {
            return traceId;
        }

        @Override
        public boolean partial() {
            return false;
        }

        @Override
        public boolean update() {
            return false;
        }

        @Override
        public void accept(TraceVisitor traceVisitor) throws Exception {
            traceVisitor.visitHeader(header);
        }
    }
}
