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
package org.glowroot.common.repo;

import java.text.DecimalFormat;
import java.util.TimeZone;

import javax.annotation.Nullable;

public class Utils {

    private Utils() {}

    public static String getPercentileWithSuffix(double percentile) {
        String percentileText = new DecimalFormat("0.#########").format(percentile);
        return percentileText + getPercentileSuffix(percentileText);
    }

    private static String getPercentileSuffix(String percentileText) {
        if (isSpecialCase(percentileText, "11") || isSpecialCase(percentileText, "12")
                || isSpecialCase(percentileText, "13")) {
            return "th";
        }
        switch (percentileText.charAt(percentileText.length() - 1)) {
            case '1':
                return "st";
            case '2':
                return "nd";
            case '3':
                return "rd";
            default:
                return "th";
        }
    }

    public static long getRollupCaptureTime(long captureTime, long intervalMillis) {
        return getRollupCaptureTime(captureTime, intervalMillis, null);
    }

    public static long getRollupCaptureTime(long captureTime, long intervalMillis,
            @Nullable TimeZone timeZone) {
        int timeZoneOffset = timeZone == null ? 0 : timeZone.getOffset(captureTime);
        // cast to long at the very end to avoid long rollover (e.g. when captureTime = Long.MAX)
        return (long) (Math.ceil((captureTime + timeZoneOffset) / (double) intervalMillis)
                * intervalMillis - timeZoneOffset);
    }

    private static boolean isSpecialCase(String percentileText, String teen) {
        return percentileText.equals(teen) || percentileText.endsWith('.' + teen)
                || percentileText.endsWith(',' + teen);
    }
}
