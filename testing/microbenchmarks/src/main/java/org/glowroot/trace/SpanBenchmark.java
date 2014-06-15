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
package org.glowroot.trace;

import org.openjdk.jmh.annotations.Benchmark;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
import org.glowroot.trace.model.TraceMetricNameImpl;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SpanBenchmark extends AbstractBenchmark {

    private TraceMetricName nestedTraceMetricName;

    @Override
    public void setup() throws Exception {
        super.setup();
        nestedTraceMetricName = new TraceMetricNameImpl("nested trace metric");
    }

    @Benchmark
    public void baselineTraceWithNoSpans() {
        Span rootSpan = pluginServices.startTrace("micro trace",
                MessageSupplier.from("micro trace"), traceMetricName);
        rootSpan.end();
    }

    // default span limit is 2000 for a given trace, after which span is ignored, so need to
    // stay under the limit to get accurate 'per-span' overhead
    @Benchmark
    public void traceWith1000Spans() {
        Span rootSpan = pluginServices.startTrace("micro trace",
                MessageSupplier.from("micro trace"), traceMetricName);
        for (int i = 0; i < 1000; i++) {
            Span span = pluginServices.startSpan(MessageSupplier.from("span"),
                    nestedTraceMetricName);
            span.end();
        }
        rootSpan.end();
    }
}
