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
import org.openjdk.jmh.annotations.Setup;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.Span;
import org.glowroot.trace.model.Trace;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceRegistryBenchmark extends AbstractBenchmark {

    private Trace trace;

    @Override
    @Setup
    public void setup() throws Exception {
        super.setup();
        // fill up the registry a bit first
        for (int i = 0; i < 10; i++) {
            pluginServices.startTrace("", MessageSupplier.from(""), traceMetricName);
        }
        Span rootSpan = pluginServices.startTrace("", MessageSupplier.from(""), traceMetricName);
        trace = traceRegistry.getCurrentTrace();
        rootSpan.end();
    }

    @Benchmark
    public void getCurrentTrace() {
        traceRegistry.getCurrentTrace();
    }

    // helps to test this in pair, otherwise need to deal with keeping registry bounded
    @Benchmark
    public void addAndRemoveTrace() {
        traceRegistry.addTrace(trace);
        traceRegistry.removeTrace(trace);
    }
}
