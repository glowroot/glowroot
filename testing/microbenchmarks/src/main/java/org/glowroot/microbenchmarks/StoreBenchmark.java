/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.microbenchmarks;

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

import org.glowroot.microbenchmarks.support.TraceEntryWorthy;
import org.glowroot.plugin.api.MessageSupplier;
import org.glowroot.plugin.api.PluginServices;
import org.glowroot.plugin.api.TimerName;
import org.glowroot.plugin.api.TraceEntry;
import org.glowroot.plugin.api.weaving.Pointcut;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class StoreBenchmark {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-microbenchmarks");
    private static final TimerName timerName =
            pluginServices.getTimerName(OnlyForTheTimerName.class);

    @Param({"false", "true"})
    private boolean store;

    @Param({"0", "100", "1000"})
    private int traceEntryCount;

    private TraceEntry rootTraceEntry;

    @Setup
    public void setup() throws Exception {
        Class<?> clazz = Class.forName("org.glowroot.collector.TransactionCollectorImpl");
        Field field = clazz.getDeclaredField("useSynchronousStore");
        field.setAccessible(true);
        field.set(null, true);
    }

    @TearDown
    public void tearDown() throws Exception {
        Class<?> clazz = Class.forName("org.glowroot.collector.TransactionCollectorImpl");
        Field field = clazz.getDeclaredField("useSynchronousStore");
        field.setAccessible(true);
        field.set(null, false);
    }

    @Benchmark
    public void execute() {
        rootTraceEntry = pluginServices.startTransaction("Microbenchmark", "micro transaction",
                MessageSupplier.from("micro transaction"), timerName);
        if (store) {
            pluginServices.setSlowTraceThreshold(0, MILLISECONDS);
        }
        TraceEntryWorthy traceEntryWorthy = new TraceEntryWorthy();
        for (int i = 0; i < traceEntryCount; i++) {
            traceEntryWorthy.doSomethingTraceEntryWorthy();
        }
        rootTraceEntry.end();
    }

    @Pointcut(className = "dummy", methodName = "dummy", methodParameterTypes = {},
            timerName = "micro transaction")
    private static class OnlyForTheTimerName {}
}
