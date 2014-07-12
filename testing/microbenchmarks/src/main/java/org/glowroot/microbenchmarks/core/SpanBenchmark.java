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
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

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
    private PointcutType pointcutType;

    private SpanWorthy spanWorthy;

    @Setup
    public void setup() {
        spanWorthy = new SpanWorthy();
    }

    @Benchmark
    @OperationsPerInvocation(2000)
    public void execute() {
        Span rootSpan = pluginServices.startTrace("Microbenchmark", "micro trace",
                MessageSupplier.from("micro trace"), traceMetricName);
        switch (pointcutType) {
            case API:
                for (int i = 0; i < 2000; i++) {
                    spanWorthy.doSomethingSpanWorthy();
                }
                break;
            case CONFIG:
                for (int i = 0; i < 2000; i++) {
                    spanWorthy.doSomethingSpanWorthy2();
                }
                break;
        }
        rootSpan.end();
    }

    @Pointcut(type = "dummy", methodName = "dummy", traceMetric = "micro trace")
    private static class OnlyForTheTraceMetricName {}
}
