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
package org.glowroot.common;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// ConcurrentLinkedQueue.remove() starts out slow and gets slower and slower throughout the
// benchmark, even though the queue size remains the same
//
// # Warmup Iteration 1: 48550.500 ns/op
// # Warmup Iteration 2: 113421.061 ns/op
// # Warmup Iteration 3: 148467.083 ns/op
// # Warmup Iteration 4: 177878.917 ns/op
// # Warmup Iteration 5: 190557.413 ns/op
// # Warmup Iteration 6: 205970.280 ns/op
// # Warmup Iteration 7: 220359.171 ns/op
// # Warmup Iteration 8: 239903.248 ns/op
// # Warmup Iteration 9: 258101.403 ns/op
// # Warmup Iteration 10: 278146.624 ns/op
// Iteration 1: 302623.108 ns/op
// Iteration 2: 323423.652 ns/op
// Iteration 3: 371802.029 ns/op
// Iteration 4: 374867.311 ns/op
// Iteration 5: 402803.141 ns/op
// Iteration 6: 424750.591 ns/op
// Iteration 7: 451644.993 ns/op
// Iteration 8: 469238.052 ns/op
// Iteration 9: 457574.279 ns/op
// Iteration 10: 463306.488 ns/op
@BenchmarkMode(AverageTime)
@OutputTimeUnit(NANOSECONDS)
@State(Thread)
public class ConcurrentLinkedQueueBenchmark {

    private Queue<Object> queue;

    @Setup
    public void setup() {
        queue = new ConcurrentLinkedQueue<Object>();
        for (int i = 0; i < 10; i++) {
            queue.add(new Object());
        }
    }

    @Benchmark
    public void addAndRemoveObject() {
        Object object = new Object();
        queue.add(object);
        queue.remove(object);
    }
}
