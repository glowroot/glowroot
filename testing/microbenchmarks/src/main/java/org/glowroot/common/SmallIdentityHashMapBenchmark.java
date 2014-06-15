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

import java.util.IdentityHashMap;
import java.util.Map;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

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
public class SmallIdentityHashMapBenchmark {

    @Param({"4", "8", "16", "32"})
    private int initialCapacity;

    @Param({"1", "2", "3", "4", "6", "8"})
    private int numObjects;

    private Map<Object, Object> map;
    private Object key;
    private Object value;

    @Setup
    public void setup() {
        map = new IdentityHashMap<Object, Object>(initialCapacity);
        key = new Object();
        value = new Object();
        for (int i = 0; i < numObjects - 1; i++) {
            map.put(new Object(), new Object());
        }
        map.put(key, value);
    }

    @Benchmark
    public Object getObject() {
        return map.get(key);
    }
}
