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
package org.informantproject.local.ui;

import javax.annotation.Nullable;

/**
 * Structure used to deserialize json post data sent to "/trace/durations" and "/trace/details".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class TraceRequest {

    private long from;
    private long to;
    private double low; // milliseconds, with nanosecond precision
    private double high; // milliseconds, with nanosecond precision
    @Nullable
    private String userIdComparator;
    @Nullable
    private String userId;
    private boolean error;
    private boolean fine;
    @Nullable
    private String extraIds;

    public long getFrom() {
        return from;
    }

    public void setFrom(long from) {
        this.from = from;
    }

    public long getTo() {
        return to;
    }

    public void setTo(long to) {
        this.to = to;
    }

    public double getLow() {
        return low;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public double getHigh() {
        return high;
    }

    public void setHigh(double high) {
        this.high = high;
    }

    @Nullable
    public String getUserIdComparator() {
        return userIdComparator;
    }

    public void setUserIdComparator(@Nullable String userIdComparator) {
        this.userIdComparator = userIdComparator;
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    public void setUserId(@Nullable String userId) {
        this.userId = userId;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public boolean isFine() {
        return fine;
    }

    public void setFine(boolean fine) {
        this.fine = fine;
    }

    @Nullable
    public String getExtraIds() {
        return extraIds;
    }

    public void setExtraIds(@Nullable String extraIds) {
        this.extraIds = extraIds;
    }
}
