/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Ticker;
import org.checkerframework.checker.nullness.qual.Nullable;

public class AsyncQueryData implements QueryData {

    private volatile String queryText;
    private final @Nullable AsyncQueryData limitExceededBucket;

    private final AtomicLong sumOfStartTicks = new AtomicLong();
    private final AtomicLong sumOfEndTicks = new AtomicLong();
    private final AtomicLong executionCount = new AtomicLong();
    private final AtomicInteger activeCount = new AtomicInteger();

    // -1 is for queries that don't even have a concept of row (e.g. http client requests which are
    // also tracked as queries)
    private final AtomicLong totalRows = new AtomicLong(-1);

    public AsyncQueryData(String queryText, @Nullable AsyncQueryData limitExceededBucket) {
        this.queryText = queryText;
        this.limitExceededBucket = limitExceededBucket;
    }

    @Override
    public String getQueryText() {
        return queryText;
    }

    @Override
    public void setQueryText(String queryText) {
        this.queryText = queryText;
    }

    @Override
    public void appendQueryText(String queryText) {
        this.queryText += queryText;
    }

    public long getTotalDurationNanos(Ticker ticker) {
        // TODO analyze worst case due to lack of atomicity, especially because it's possible for
        // async query to be active at the end of a transaction
        if (activeCount.get() > 0) {
            long currTick = ticker.read();
            return sumOfEndTicks.get() + currTick * activeCount.get() - sumOfStartTicks.get();
        }
        return sumOfEndTicks.get() - sumOfStartTicks.get();
    }

    public long getExecutionCount() {
        return executionCount.get();
    }

    public boolean hasTotalRows() {
        return totalRows.get() != -1;
    }

    public long getTotalRows() {
        long totalRows = this.totalRows.get();
        return totalRows == -1 ? 0 : totalRows;
    }

    public boolean isActive() {
        return activeCount.get() > 0;
    }

    @Override
    public void start(long startTick, long batchSize) {
        sumOfStartTicks.getAndAdd(startTick);
        executionCount.getAndAdd(batchSize);
        activeCount.getAndIncrement();
        if (limitExceededBucket != null) {
            limitExceededBucket.start(startTick, batchSize);
        }
    }

    @Override
    public void end(long endTick) {
        sumOfEndTicks.getAndAdd(endTick);
        activeCount.getAndDecrement();
        if (limitExceededBucket != null) {
            limitExceededBucket.end(endTick);
        }
    }

    @Override
    public void setHasTotalRows() {
        totalRows.compareAndSet(-1, 0);
        if (limitExceededBucket != null) {
            limitExceededBucket.setHasTotalRows();
        }
    }

    @Override
    public void incrementRowCount(long inc) {
        totalRows.compareAndSet(-1, 0);
        totalRows.getAndAdd(inc);
        if (limitExceededBucket != null) {
            limitExceededBucket.incrementRowCount(inc);
        }
    }

    @Override
    public void extend(long startTick) {
        sumOfStartTicks.getAndAdd(startTick);
        activeCount.getAndIncrement();
        if (limitExceededBucket != null) {
            limitExceededBucket.extend(startTick);
        }
    }
}
