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
import org.glowroot.api.TraceEntry;
import org.glowroot.api.MetricName;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.microbenchmarks.core.support.MetricWorthy;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class TransactionMetricBenchmark {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-microbenchmarks");

    @Param
    private PointcutType pointcutType;

    private TraceEntry rootTraceEntry;
    private MetricWorthy metricWorthy;

    @Setup
    public void setup() {
        MetricName metricName =
                pluginServices.getMetricName(OnlyForTheMetricName.class);
        rootTraceEntry = pluginServices.startTransaction("Microbenchmark", "micro transaction",
                MessageSupplier.from("micro transaction"), metricName);
        metricWorthy = new MetricWorthy();
    }

    @TearDown
    public void tearDown() {
        rootTraceEntry.end();
    }

    @Benchmark
    public void execute() {
        switch (pointcutType) {
            case API:
                metricWorthy.doSomethingMetricWorthy();
                break;
            case CONFIG:
                metricWorthy.doSomethingMetricWorthy2();
                break;
        }
    }

    @Pointcut(className = "dummy", methodName = "dummy", metricName = "micro transaction")
    private static class OnlyForTheMetricName {}
}
