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
package org.glowroot.agent.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;

public class Tickers {

    private static final boolean USE_DUMMY_TICKER =
            Boolean.getBoolean("glowroot.internal.dummyTicker");

    private Tickers() {}

    // normally Ticker should be injected, but in some memory sensitive classes it can be cached
    // in a static field
    public static Ticker getTicker() {
        return getTicker(USE_DUMMY_TICKER);
    }

    // Nano times roll over every 292 years, so it is important to test differences between nano
    // times instead of direct comparison (e.g. nano2 - nano1 >= 0, not nano1 <= nano2)
    // (see http://java.sun.com/javase/7/docs/api/java/lang/System.html#nanoTime())
    public static boolean lessThanOrEqual(long tick1, long tick2) {
        return tick2 - tick1 >= 0;
    }

    @VisibleForTesting
    static Ticker getTicker(boolean dummyTicker) {
        if (dummyTicker) {
            return new DummyTicker();
        } else {
            return Ticker.systemTicker();
        }
    }

    private static class DummyTicker extends Ticker {
        @Override
        public long read() {
            return 0;
        }
    }
}
