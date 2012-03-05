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

    public String getExtraIds() {
        return extraIds;
    }

    public void setExtraIds(String extraIds) {
        this.extraIds = extraIds;
    }
}
