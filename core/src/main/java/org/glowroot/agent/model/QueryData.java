/*
 * Copyright 2015 the original author or authors.
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

import com.google.common.base.Ticker;

import org.glowroot.collector.spi.Query;
import org.glowroot.common.util.Tickers;
import org.glowroot.plugin.api.transaction.Timer;

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
public class QueryData implements Timer, Query {

    private static final Ticker ticker = Tickers.getTicker();

    private final String queryType;
    private final String queryText;
    private final @Nullable QueryData nextQueryData;

    // nanosecond rollover (292 years) isn't a concern for total time on a single transaction
    private long totalNanos;
    private long executionCount;
    private long totalRows;

    private long startTick;
    private int selfNestingLevel;

    QueryData(String queryType, String queryText, @Nullable QueryData nextQueryData) {
        this.queryType = queryType;
        this.queryText = queryText;
        this.nextQueryData = nextQueryData;
    }

    String getQueryType() {
        return queryType;
    }

    @Override
    public String queryText() {
        return queryText;
    }

    @Nullable
    QueryData getNextQueryData() {
        return nextQueryData;
    }

    public void start(long startTick, long batchSize) {
        if (selfNestingLevel++ == 0) {
            this.startTick = startTick;
            executionCount += batchSize;
        }
    }

    @Override
    public void stop() {
        if (--selfNestingLevel == 0) {
            endInternal(ticker.read());
        }
    }

    public void end(long endTick) {
        if (--selfNestingLevel == 0) {
            endInternal(endTick);
        }
    }

    public void incrementRowCount(long inc) {
        totalRows += inc;
    }

    @Override
    public double totalNanos() {
        return totalNanos;
    }

    // only called after transaction completion
    @Override
    public long executionCount() {
        return executionCount;
    }

    // only called after transaction completion
    @Override
    public long totalRows() {
        return totalRows;
    }

    public void extend(long startTick) {
        if (selfNestingLevel++ == 0) {
            // restarting a previously stopped execution, so need to decrement count
            this.startTick = startTick;
        }
    }

    private void endInternal(long endTick) {
        totalNanos += endTick - startTick;
    }
}
