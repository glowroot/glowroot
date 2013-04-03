/**
 * Copyright 2013 the original author or authors.
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
package io.informant.trace;

import io.informant.trace.model.Trace;

import java.util.Collection;
import java.util.Collections;

import com.google.caliper.Runner;
import com.google.caliper.SimpleBenchmark;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;

/**
 * This micro benchmark helps justify using synchronized ArrayList over ConcurrentLinkedQueue for
 * {@code attributes} and {@code metrics} fields in {@link Trace}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ThreadSafeCollectionOfTenBenchmark {

    public static void main(String[] args) {
        Runner runner = new Runner();
        runner.run(new String[] {SynchronizedArrayBenchmark.class.getName()});
        runner.run(new String[] {SynchronizedArrayWithBadCapacityBenchmark.class.getName()});
        runner.run(new String[] {ConcurrentLinkedQueueBenchmark.class.getName()});
        runner.run(new String[] {CopyOnWriteArrayListBenchmark.class.getName()});
    }

    // 119.04 ns; σ=3.98 ns @ 10 trials
    public static class SynchronizedArrayBenchmark extends ArrayBenchmark {
        @Override
        public Collection<Object> createCollection() {
            return Collections.synchronizedList(Lists.newArrayListWithCapacity(20));
        }
        public void timeSynchronizedArrayList(int reps) {
            super.timeList(reps);
        }
    }

    // 151.68 ns; σ=14.54 ns @ 10 trials
    public static class SynchronizedArrayWithBadCapacityBenchmark extends ArrayBenchmark {
        @Override
        public Collection<Object> createCollection() {
            return Collections.synchronizedList(Lists.newArrayListWithCapacity(8));
        }
        public void timeSynchronizedArrayList(int reps) {
            super.timeList(reps);
        }
    }

    // 258.44 ns; sigma=2.15 ns @ 3 trials
    public static class ConcurrentLinkedQueueBenchmark extends ArrayBenchmark {
        @Override
        public Collection<Object> createCollection() {
            return Queues.newConcurrentLinkedQueue();
        }
        public void timeConcurrentLinkedQueue(int reps) {
            super.timeList(reps);
        }
    }

    // 550.39 ns; σ=10.73 ns @ 10 trials
    public static class CopyOnWriteArrayListBenchmark extends ArrayBenchmark {
        @Override
        public Collection<Object> createCollection() {
            return Lists.newCopyOnWriteArrayList();
        }
        public void timeCopyOnWriteArrayList(int reps) {
            super.timeList(reps);
        }
    }

    public static abstract class ArrayBenchmark extends SimpleBenchmark {
        public Collection<Object> collection;
        public abstract Collection<Object> createCollection();
        public void timeList(int reps) {
            for (int i = 0; i < reps; i++) {
                collection = createCollection();
                for (int j = 0; j < 10; j++) {
                    collection.add(new Object());
                }
            }
        }
    }

}
