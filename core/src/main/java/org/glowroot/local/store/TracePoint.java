/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.local.store;

import com.google.common.base.Objects;

import org.glowroot.markers.Immutable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class TracePoint {

    private final String id;
    private final long captureTime;
    private final double duration; // nanoseconds
    private final boolean error;

    public static TracePoint from(String id, long captureTime, double duration, boolean error) {
        return new TracePoint(id, captureTime, duration, error);
    }

    private TracePoint(String id, long captureTime, double duration, boolean error) {
        this.id = id;
        this.captureTime = captureTime;
        this.duration = duration;
        this.error = error;
    }

    public String getId() {
        return id;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    public double getDuration() {
        return duration;
    }

    public boolean isError() {
        return error;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("captureTime", captureTime)
                .add("duration", duration)
                .add("error", error)
                .toString();
    }
}
