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
package org.glowroot.transaction.model;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Ticker;

import org.glowroot.api.Timer;
import org.glowroot.common.Tickers;

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
public class QueryData implements Timer {

    private static final Ticker ticker = Tickers.getTicker();

    // nanosecond rollover (292 years) isn't a concern for total time on a single transaction
    private long totalTime;
    private long executionCount;
    private long totalRows;

    private long startTick;
    private int selfNestingLevel;

    // safe to be called from another thread
    public void writeValue(String queryType, String queryText, JsonGenerator jg)
            throws IOException {
        jg.writeStartObject();
        jg.writeStringField("queryType", queryType);
        jg.writeStringField("queryText", queryText);
        boolean active = selfNestingLevel > 0;
        if (active) {
            // try to grab a quick, consistent view, but no guarantee on consistency since the
            // transaction is active
            //
            // grab total before curr, to avoid case where total is updated in between
            // these two lines and then "total + curr" would overstate the correct value
            // (it seems better to understate the correct value if there is an update to the
            // timer values in between these two lines)
            long theTotalTime = totalTime;
            long theTotalRows = totalRows;
            // capture startTick before ticker.read() so curr is never < 0
            long theStartTick = startTick;
            long curr = ticker.read() - theStartTick;
            jg.writeNumberField("totalTime", theTotalTime + curr);
            jg.writeNumberField("executionCount", executionCount);
            jg.writeNumberField("totalRows", theTotalRows);
            jg.writeBooleanField("active", true);
        } else {
            jg.writeNumberField("totalTime", totalTime);
            jg.writeNumberField("executionCount", executionCount);
            jg.writeNumberField("totalRows", totalRows);
        }
        jg.writeEndObject();
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

    // only called after transaction completion
    public long getTotalTime() {
        return totalTime;
    }

    // only called after transaction completion
    public long getExecutionCount() {
        return executionCount;
    }

    // only called after transaction completion
    public long getTotalRows() {
        return totalRows;
    }

    public void extend(long startTick) {
        if (selfNestingLevel++ == 0) {
            // restarting a previously stopped execution, so need to decrement count
            this.startTick = startTick;
        }
    }

    public void extend() {
        if (selfNestingLevel++ == 0) {
            // restarting a previously stopped execution, so need to decrement count
            this.startTick = ticker.read();
        }
    }

    boolean isActive() {
        return selfNestingLevel > 0;
    }

    private void endInternal(long endTick) {
        totalTime += endTick - startTick;
    }
}
