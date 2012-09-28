/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.trace;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.Objects;

/**
 * Structure used in the response to "/trace/points".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class TraceSnapshotPoint {

    private final String id;
    private final long capturedAt;
    private final double duration; // nanoseconds
    private final boolean completed;
    private final boolean error;

    public static TraceSnapshotPoint from(String id, long capturedAt, double duration,
            boolean completed, boolean error) {
        return new TraceSnapshotPoint(id, capturedAt, duration, completed, error);
    }

    private TraceSnapshotPoint(String id, long capturedAt, double duration, boolean completed,
            boolean error) {

        this.id = id;
        this.capturedAt = capturedAt;
        this.duration = duration;
        this.completed = completed;
        this.error = error;
    }

    public String getId() {
        return id;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public double getDuration() {
        return duration;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isError() {
        return error;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof TraceSnapshotPoint)) {
            return false;
        }
        TraceSnapshotPoint other = (TraceSnapshotPoint) o;
        return Objects.equal(id, other.id)
                && Objects.equal(capturedAt, other.capturedAt)
                && Objects.equal(duration, other.duration)
                && Objects.equal(error, other.error)
                && Objects.equal(completed, other.completed);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, capturedAt, duration, completed, error);
    }
}
