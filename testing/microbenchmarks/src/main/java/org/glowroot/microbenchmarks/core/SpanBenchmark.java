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
package org.glowroot.microbenchmarks.core;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.microbenchmarks.core.support.SpanWorthy;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SpanBenchmark {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-microbenchmarks");
    private static final TraceMetricName traceMetricName =
            pluginServices.getTraceMetricName(OnlyForTheTraceMetricName.class);

    @Param
    private SpanWorthyMethod spanWorthyMethod;

    private Span rootSpan;
    private SpanWorthy spanWorthy;

    private int count;

    @Setup
    public void setup() {
        rootSpan = pluginServices.startTrace("Microbenchmark", "micro trace",
                MessageSupplier.from("micro trace"), traceMetricName);
        spanWorthy = new SpanWorthy();
        count = 0;
    }

    @TearDown
    public void tearDown() {
        rootSpan.end();
    }

    @Benchmark
    public void span() {
        switch (spanWorthyMethod) {
            case ONE:
                spanWorthy.doSomethingSpanWorthy();
                break;
            case TWO:
                spanWorthy.doSomethingSpanWorthy2();
                break;
        }
        if (++count % 2000 == 0) {
            // default max spans is 2000, so need to create new trace, otherwise additional spans
            // will be shortcut and average span time will be underestimated
            //
            // this adds trace overhead every 2000th span
            rootSpan.end();
            rootSpan = pluginServices.startTrace("Microbenchmark", "micro trace",
                    MessageSupplier.from("micro trace"), traceMetricName);
        }
    }

    public static enum SpanWorthyMethod {
        ONE, TWO
    }

    @Pointcut(type = "dummy", methodName = "dummy", traceMetric = "micro trace")
    private static class OnlyForTheTraceMetricName {}
}
