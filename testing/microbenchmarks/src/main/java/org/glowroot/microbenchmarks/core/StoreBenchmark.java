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

import java.lang.reflect.Field;
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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class StoreBenchmark {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-microbenchmarks");
    private static final TraceMetricName traceMetricName =
            pluginServices.getTraceMetricName(OnlyForTheTraceMetricName.class);

    @Param({"false", "true"})
    private boolean store;

    @Param({"0", "100", "1000"})
    private int spanCount;

    private Span rootSpan;

    @Setup
    public void setup() throws Exception {
        Class<?> clazz = Class.forName("org.glowroot.collector.TraceCollectorImpl");
        Field field = clazz.getDeclaredField("useSynchronousStore");
        field.setAccessible(true);
        field.set(null, true);
    }

    @TearDown
    public void tearDown() throws Exception {
        Class<?> clazz = Class.forName("org.glowroot.collector.TraceCollectorImpl");
        Field field = clazz.getDeclaredField("useSynchronousStore");
        field.setAccessible(true);
        field.set(null, false);
    }

    @Benchmark
    public void execute() {
        rootSpan = pluginServices.startTrace("Microbenchmark", "micro trace",
                MessageSupplier.from("micro trace"), traceMetricName);
        if (store) {
            pluginServices.setTraceStoreThreshold(0, MILLISECONDS);
        }
        SpanWorthy spanWorthy = new SpanWorthy();
        for (int i = 0; i < spanCount; i++) {
            spanWorthy.doSomethingSpanWorthy();
        }
        rootSpan.end();
    }

    @Pointcut(type = "dummy", methodName = "dummy", traceMetric = "micro trace")
    private static class OnlyForTheTraceMetricName {}
}
