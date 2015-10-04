/*
 * Copyright 2011-2015 the original author or authors.
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

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

// the string representation of the unique identifier is lazily constructed since it is only needed
// for traces which are stored and/or viewed in-flight
class TraceUniqueId {

    // used to populate id (below)
    private static final AtomicLong idCounter = new AtomicLong();

    private static final long HEX_DIGITS = 16;
    // at most 6 bytes in hex form
    private static final long MAX_ID = (long) Math.pow(HEX_DIGITS, 6);

    private final long traceStartTime;
    private final long id;

    public TraceUniqueId(long traceStartTime) {
        this.traceStartTime = traceStartTime;
        id = idCounter.getAndIncrement();
    }

    public String get() {
        return twelveDigitHex(traceStartTime) + BigInteger.valueOf(id % MAX_ID).toString(16);
    }

    @VisibleForTesting
    static String twelveDigitHex(long x) {
        String s = BigInteger.valueOf(x).toString(16);
        if (s.length() == 11) {
            // this is the case for dates between Nov 3, 2004 and Jun 23, 2527 so it seems worth
            // optimizing :-)
            return '0' + s;
        }
        if (s.length() == 12) {
            return s;
        }
        if (s.length() > 12) {
            // Aug 1 of the year 10889 and beyond!
            return s.substring(s.length() - 12);
        }
        // living in the past?
        return Strings.padStart(s, 12, '0');
    }
}
