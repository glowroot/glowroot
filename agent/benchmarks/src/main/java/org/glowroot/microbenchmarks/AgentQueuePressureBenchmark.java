/*
 * Copyright 2026 the original author or authors.
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(8)
@State(Scope.Benchmark)
public class AgentQueuePressureBenchmark {

    @Param({"0", "64"})
    private int retainOpen;

    private final List<Thread> holders = new ArrayList<Thread>();
    private CountDownLatch release;

    @Setup(Level.Trial)
    public void setupHolders() throws InterruptedException {
        release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(retainOpen);
        for (int i = 0; i < retainOpen; i++) {
            Thread t = new Thread(new Holder(release, started), "queue-holder-" + i);
            t.setDaemon(true);
            holders.add(t);
            t.start();
        }
        if (retainOpen > 0 && !started.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("retain holders did not start in time");
        }
    }

    @TearDown(Level.Trial)
    public void tearDownHolders() throws InterruptedException {
        release.countDown();
        for (Thread t : holders) {
            t.join(5000);
        }
        holders.clear();
    }

    @Benchmark
    public void execute() {
        churnTransaction();
    }

    // woven via META-INF/glowroot.plugin.json capturePoints
    public void churnTransaction() {}

    // woven via META-INF/glowroot.plugin.json capturePoints
    public static void retainTransaction(CountDownLatch release, CountDownLatch started)
            throws InterruptedException {
        started.countDown();
        release.await();
    }

    private static class Holder implements Runnable {
        private final CountDownLatch release;
        private final CountDownLatch started;

        private Holder(CountDownLatch release, CountDownLatch started) {
            this.release = release;
            this.started = started;
        }

        @Override
        public void run() {
            try {
                retainTransaction(release, started);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
