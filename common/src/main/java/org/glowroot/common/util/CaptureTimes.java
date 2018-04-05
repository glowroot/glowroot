/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.common.util;

import java.util.TimeZone;

import org.checkerframework.checker.nullness.qual.Nullable;

public class CaptureTimes {

    private CaptureTimes() {}

    public static long getRollup(long captureTime, long intervalMillis) {
        return getRollup(captureTime, intervalMillis, null);
    }

    public static long getRollup(long captureTime, long intervalMillis,
            @Nullable TimeZone timeZone) {
        int timeZoneOffset = timeZone == null ? 0 : timeZone.getOffset(captureTime);
        // cast to long at the very end to avoid long rollover (e.g. when captureTime = Long.MAX)
        return (long) (Math.ceil((captureTime + timeZoneOffset) / (double) intervalMillis)
                * intervalMillis - timeZoneOffset);
    }
}
