/*
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
package org.glowroot.collector;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import checkers.lock.quals.GuardedBy;
import com.google.common.collect.Maps;

import org.glowroot.common.Clock;
import org.glowroot.markers.Singleton;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class Aggregator {

    @GuardedBy("lock")
    private volatile Aggregates currentAggregates;
    private final Object lock = new Object();

    private final ScheduledExecutorService scheduledExecutor;
    private final AggregateRepository aggregateRepository;
    private final Clock clock;

    private final long fixedAggregateIntervalMillis;

    Aggregator(ScheduledExecutorService scheduledExecutor, AggregateRepository aggregateRepository,
            Clock clock, long fixedAggregateIntervalSeconds) {
        this.scheduledExecutor = scheduledExecutor;
        this.aggregateRepository = aggregateRepository;
        this.clock = clock;
        this.fixedAggregateIntervalMillis = fixedAggregateIntervalSeconds * 1000;
        currentAggregates = new Aggregates(clock.currentTimeMillis());
        // store aggregate record for each interval even if no data
        scheduledExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                flush();
            }
        }, 10, 10, SECONDS);
    }

    long add(String grouping, long duration) {
        // this first synchronized block is to ensure atomicity between updates and flushes
        synchronized (lock) {
            long captureTime = clock.currentTimeMillis();
            if (captureTime > currentAggregates.captureTime) {
                final Aggregates completedAggregates = currentAggregates;
                currentAggregates = new Aggregates(captureTime);
                // flush in separate thread
                scheduledExecutor.execute(new Runnable() {
                    public void run() {
                        flush(completedAggregates);
                    }
                });
            }
            // this second synchronized block is to ensure visibility of updates to this particular
            // currentAggregates
            synchronized (currentAggregates) {
                currentAggregates.add(grouping, duration);
            }
            return captureTime;
        }
    }

    private void flush() {
        Aggregates completedAggregates = null;
        // this synchronized block is to ensure atomicity between updates and flushes
        synchronized (lock) {
            if (clock.currentTimeMillis() > currentAggregates.captureTime) {
                completedAggregates = currentAggregates;
                currentAggregates = new Aggregates(clock.currentTimeMillis());
            }
        }
        if (completedAggregates != null) {
            // the actual flushing does not block the add() method above
            flush(completedAggregates);
        }
    }

    private void flush(Aggregates aggregates) {
        // this synchronized block is to ensure visibility of updates to this particular
        // currentAggregates
        synchronized (aggregates) {
            aggregateRepository.store(aggregates.captureTime, aggregates.aggregate,
                    aggregates.groupingAggregates);
        }
    }

    private class Aggregates {

        private final long captureTime;
        private final Aggregate aggregate = new Aggregate();
        private final Map<String, Aggregate> groupingAggregates = Maps.newHashMap();

        private Aggregates(long currentTime) {
            this.captureTime = (long) Math.ceil(currentTime
                    / (double) fixedAggregateIntervalMillis) * fixedAggregateIntervalMillis;
        }

        private void add(String grouping, long duration) {
            aggregate.add(duration);
            Aggregate groupingAggregate = groupingAggregates.get(grouping);
            if (groupingAggregate == null) {
                groupingAggregate = new Aggregate();
                groupingAggregates.put(grouping, groupingAggregate);
            }
            groupingAggregate.add(duration);
        }
    }
}
