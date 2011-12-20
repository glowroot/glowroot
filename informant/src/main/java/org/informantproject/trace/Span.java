/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.trace;

import org.informantproject.api.SpanContextMap;
import org.informantproject.api.SpanDetail;

/**
 * The "span" terminology is borrowed from <a
 * href="http://research.google.com/pubs/pub36356.html">Dapper</a>.
 * 
 * This must support updating by a single thread and reading by multiple threads.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class Span {

    private final SpanDetail spanDetail;

    private final long traceStartTime;
    private final long startTime;
    private volatile long endTime;
    private volatile boolean completed;

    // index is per trace and starts at 0
    private final int index;
    private final int parentIndex;

    // level is just a convenience for output
    private final int level;

    Span(SpanDetail spanDetail, long traceStartTime, long startTime, int index, int parentIndex,
            int level) {

        this.spanDetail = spanDetail;
        this.traceStartTime = traceStartTime;
        this.startTime = startTime;
        this.index = index;
        this.parentIndex = parentIndex;
        this.level = level;
    }

    public String getDescription() {
        return spanDetail.getDescription();
    }

    public SpanContextMap getContextMap() {
        return spanDetail.getContextMap();
    }

    public SpanDetail getSpanDetail() {
        return spanDetail;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    // offset in nanoseconds from beginning of trace
    public long getOffset() {
        return startTime - traceStartTime;
    }

    // duration of span in nanoseconds
    public long getDuration() {
        return endTime - startTime;
    }

    public boolean isCompleted() {
        return completed;
    }

    public int getIndex() {
        return index;
    }

    public int getParentIndex() {
        return parentIndex;
    }

    public int getLevel() {
        return level;
    }

    void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    void setCompleted(boolean completed) {
        this.completed = completed;
    }
}
