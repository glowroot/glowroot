/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.trace.model;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.Mutable;
import com.google.common.base.Strings;

/**
 * The unique identifier for a trace. The string representation of the unique identifier is lazily
 * constructed since it is not needed in the majority of the cases (it is only needed for traces
 * which are either stored and/or viewed in-flight).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class TraceUniqueId {

    // used to populate id (below)
    @Mutable
    private static final AtomicLong idCounter = new AtomicLong();

    private static final long MAX_ID = (long) Math.pow(16, 6); // at most 6 bytes in hex form

    private final long traceStart;
    private final long id;

    public TraceUniqueId(long traceStart) {
        this.traceStart = traceStart;
        id = idCounter.getAndIncrement();
    }

    public String get() {
        return twelveDigitHex(traceStart) + BigInteger.valueOf(id % MAX_ID).toString(16);
    }

    private static String twelveDigitHex(long x) {
        String s = BigInteger.valueOf(x).toString(16);
        if (s.length() > 12) {
            return s.substring(s.length() - 12);
        } else if (s.length() == 12) {
            return s;
        } else if (s.length() == 11) {
            // this is the case for dates between Nov 3, 2004 and Jun 23, 2527 so it seems worth
            // optimizing
            return '0' + s;
        } else {
            return Strings.padStart(s, 12, '0');
        }
    }

    @Override
    public String toString() {
        return get();
    }
}
