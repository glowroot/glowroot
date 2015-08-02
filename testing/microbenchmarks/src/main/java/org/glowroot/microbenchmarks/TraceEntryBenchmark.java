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

import org.glowroot.microbenchmarks.support.TraceEntryWorthy;
import org.glowroot.plugin.api.Agent;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.TimerName;
import org.glowroot.plugin.api.transaction.TraceEntry;
import org.glowroot.plugin.api.transaction.TransactionService;
import org.glowroot.plugin.api.weaving.Pointcut;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class TraceEntryBenchmark {

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final TimerName timerName =
            transactionService.getTimerName(OnlyForTheTimerName.class);

    @Param
    private PointcutType pointcutType;

    private TraceEntryWorthy traceEntryWorthy;

    @Setup
    public void setup() {
        traceEntryWorthy = new TraceEntryWorthy();
    }

    @Benchmark
    @OperationsPerInvocation(2000)
    public void execute() {
        TraceEntry traceEntry = transactionService.startTransaction("Microbenchmark",
                "micro transaction", MessageSupplier.from("micro transaction"), timerName);
        switch (pointcutType) {
            case API:
                for (int i = 0; i < 2000; i++) {
                    traceEntryWorthy.doSomethingTraceEntryWorthy();
                }
                break;
            case CONFIG:
                for (int i = 0; i < 2000; i++) {
                    traceEntryWorthy.doSomethingTraceEntryWorthy2();
                }
                break;
        }
        traceEntry.end();
    }

    @Pointcut(className = "dummy", methodName = "dummy", methodParameterTypes = {},
            timerName = "micro transaction")
    private static class OnlyForTheTimerName {}
}
