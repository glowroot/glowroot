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

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The unique identifier for a trace. The string representation of the unique identifier is lazily
 * constructed since it is not needed in the majority of the cases (it is only needed for traces
 * which are either persisted and/or viewed in-flight).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class TraceUniqueId {

    // used to populate id (below)
    private static final AtomicInteger idCounter = new AtomicInteger();

    private final long startTimeMillis;
    private final int id;

    TraceUniqueId(long startTimeMillis) {
        this.startTimeMillis = startTimeMillis;
        id = idCounter.getAndIncrement();
    }

    public String get() {
        return startTimeMillis + "-" + BigInteger.valueOf(id).toString(16);
    }
}
