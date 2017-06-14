/*
 * Copyright 2015-2017 the original author or authors.
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

import javax.annotation.Nullable;

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
public class QueryData {

    private final String queryType;
    private final String queryText;
    private final @Nullable QueryData nextQueryData;
    private final @Nullable QueryData limitExceededBucket;

    // nanosecond rollover (292 years) isn't a concern for total time on a single transaction
    private long totalDurationNanos;
    private long executionCount;

    // this is needed to differentiate between queries that return no rows, and queries which don't
    // even have a concept of row (e.g. http client requests which are also tracked as queries)
    private boolean hasTotalRows;
    private long totalRows;

    private long startTick;
    private int selfNestingLevel;

    public QueryData(String queryType, String queryText, @Nullable QueryData nextQueryData,
            @Nullable QueryData limitExceededBucket) {
        this.queryType = queryType;
        this.queryText = queryText;
        this.nextQueryData = nextQueryData;
        this.limitExceededBucket = limitExceededBucket;
    }

    public String getQueryType() {
        return queryType;
    }

    public String getQueryText() {
        return queryText;
    }

    public @Nullable QueryData getNextQueryData() {
        return nextQueryData;
    }

    public void start(long startTick, long batchSize) {
        if (selfNestingLevel++ == 0) {
            this.startTick = startTick;
            executionCount += batchSize;
        }
        if (limitExceededBucket != null) {
            limitExceededBucket.start(startTick, batchSize);
        }
    }

    public long getTotalDurationNanos() {
        return totalDurationNanos;
    }

    // only called after transaction completion
    public long getExecutionCount() {
        return executionCount;
    }

    // only called after transaction completion
    public boolean hasTotalRows() {
        return hasTotalRows;
    }

    // only called after transaction completion
    public long getTotalRows() {
        return totalRows;
    }

    void end(long endTick) {
        if (--selfNestingLevel == 0) {
            endInternal(endTick);
        }
        if (limitExceededBucket != null) {
            limitExceededBucket.end(endTick);
        }
    }

    void setHasTotalRows() {
        hasTotalRows = true;
        if (limitExceededBucket != null) {
            limitExceededBucket.setHasTotalRows();
        }
    }

    void incrementRowCount(long inc) {
        hasTotalRows = true;
        totalRows += inc;
        if (limitExceededBucket != null) {
            limitExceededBucket.incrementRowCount(inc);
        }
    }

    void extend(long startTick) {
        if (selfNestingLevel++ == 0) {
            this.startTick = startTick;
        }
        if (limitExceededBucket != null) {
            limitExceededBucket.extend(startTick);
        }
    }

    private void endInternal(long endTick) {
        totalDurationNanos += endTick - startTick;
    }
}
