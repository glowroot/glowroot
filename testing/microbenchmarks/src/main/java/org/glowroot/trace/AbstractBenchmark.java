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

import java.io.File;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceMetricName;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigModule;
import org.glowroot.container.TempDirs;
import org.glowroot.trace.model.Trace;
import org.glowroot.trace.model.TraceMetricNameImpl;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@BenchmarkMode(AverageTime)
@OutputTimeUnit(NANOSECONDS)
@State(Thread)
public abstract class AbstractBenchmark {

    private File dataDir;
    private ScheduledExecutorService scheduledExecutor;

    protected TraceRegistry traceRegistry;
    protected PluginServices pluginServices;
    protected TraceMetricName traceMetricName;

    @Setup
    public void setup() throws Exception {
        dataDir = TempDirs.createTempDir("glowroot-microbenchmark");
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        traceRegistry = new TraceRegistry();
        ConfigModule configModule = new ConfigModule(null, dataDir, false);
        TraceMetricNameCache traceMetricNameCache = new TraceMetricNameCache();
        FineProfileScheduler fineProfileScheduler = new FineProfileScheduler(scheduledExecutor,
                configModule.getConfigService(), Ticker.systemTicker(), new Random());
        pluginServices = PluginServicesImpl.create(traceRegistry, new NopTraceCollector(),
                configModule.getConfigService(), traceMetricNameCache, null, fineProfileScheduler,
                Ticker.systemTicker(), Clock.systemClock(),
                configModule.getPluginDescriptorCache(), null);
        traceMetricName = new TraceMetricNameImpl("micro trace");
    }

    @TearDown
    public void tearDown() throws Exception {
        scheduledExecutor.shutdown();
        TempDirs.deleteRecursively(dataDir);
    }

    private static class NopTraceCollector implements TraceCollector {
        @Override
        public void onCompletedTrace(Trace trace) {}
        @Override
        public void onStuckTrace(Trace trace) {}
    }
}
