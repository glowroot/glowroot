/*
 * Copyright 2015-2018 the original author or authors.
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

import com.google.common.base.Ticker;
import org.checkerframework.checker.nullness.qual.Nullable;

// TODO update this comment that was copied from TimerImpl
//
// instances are updated by a single thread, but can be read by other threads
// memory visibility is therefore an issue for the reading threads
//
// memory visibility could be guaranteed by making selfNestingLevel volatile
//
// selfNestingLevel is written after other fields are written and it is read before
// other fields are read, so it could be used to create a memory barrier and make the latest values
// of the other fields visible to the reading thread
//
// but benchmarking shows making selfNestingLevel non-volatile reduces timer capture overhead
// from 88 nanoseconds down to 41 nanoseconds, which is very good since System.nanoTime() takes 17
// nanoseconds and each timer capture has to call it twice
//
// the down side is that the latest updates to timers for transactions that are captured
// in-flight (e.g. partial traces and active traces displayed in the UI) may not be visible
//
// all timing data is in nanoseconds
public class SyncQueryData implements QueryData {

    private final String queryType;
    private final String queryText;
    private final @Nullable SyncQueryData nextQueryData;
    private final @Nullable SyncQueryData limitExceededBucket;

    // nanosecond rollover (292 years) isn't a concern for total time on a single transaction
    private long sumOfStartTicks;
    private long sumOfEndTicks;
    private long executionCount;
    private long activeCount;

    // -1 is for queries that don't even have a concept of row (e.g. http client requests which are
    // also tracked as queries)
    private long totalRows = -1;

    public SyncQueryData(String queryType, String queryText, @Nullable SyncQueryData nextQueryData,
            @Nullable SyncQueryData limitExceededBucket) {
        this.queryType = queryType;
        this.queryText = queryText;
        this.nextQueryData = nextQueryData;
        this.limitExceededBucket = limitExceededBucket;
    }

    public String getQueryType() {
        return queryType;
    }

    @Override
    public String getQueryText() {
        return queryText;
    }

    public @Nullable SyncQueryData getNextQueryData() {
        return nextQueryData;
    }

    @Override
    public void start(long startTick, long batchSize) {
        sumOfStartTicks += startTick;
        executionCount += batchSize;
        activeCount++;
        if (limitExceededBucket != null) {
            limitExceededBucket.start(startTick, batchSize);
        }
    }

    public long getTotalDurationNanos(Ticker ticker) {
        // TODO analyze worst case due to lack of atomicity
        if (activeCount > 0) {
            long currTick = ticker.read();
            return sumOfEndTicks + currTick * activeCount - sumOfStartTicks;
        }
        return sumOfEndTicks - sumOfStartTicks;
    }

    public long getExecutionCount() {
        return executionCount;
    }

    public boolean hasTotalRows() {
        return totalRows != -1;
    }

    public long getTotalRows() {
        return totalRows == -1 ? 0 : totalRows;
    }

    public boolean isActive() {
        return activeCount > 0;
    }

    @Override
    public void end(long endTick) {
        sumOfEndTicks += endTick;
        activeCount--;
        if (limitExceededBucket != null) {
            limitExceededBucket.end(endTick);
        }
    }

    @Override
    public void setHasTotalRows() {
        if (totalRows == -1) {
            totalRows = 0;
        }
        if (limitExceededBucket != null) {
            limitExceededBucket.setHasTotalRows();
        }
    }

    @Override
    public void incrementRowCount(long inc) {
        if (totalRows == -1) {
            totalRows = 0;
        }
        totalRows += inc;
        if (limitExceededBucket != null) {
            limitExceededBucket.incrementRowCount(inc);
        }
    }

    @Override
    public void extend(long startTick) {
        sumOfStartTicks += startTick;
        activeCount++;
        if (limitExceededBucket != null) {
            limitExceededBucket.extend(startTick);
        }
    }
}
