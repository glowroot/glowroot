/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.ui;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import com.google.common.base.Function;
import org.junit.Test;

import org.glowroot.ui.ReportJsonService.ROLLUP;
import org.glowroot.ui.ReportJsonService.RollupCaptureTimeFn;

import static java.util.concurrent.TimeUnit.HOURS;
import static org.assertj.core.api.Assertions.assertThat;

public class ReportJsonServiceTest {

    @Test
    public void testRollupCaptureTime() throws ParseException {
        testRollupCaptureTime("UTC");
        testRollupCaptureTime("America/Los_Angeles");
    }

    @Test
    public void testRollupInterval() throws ParseException {
        testRollupIntervalMillis(ROLLUP.HOURLY, "UTC", "20161127T00", HOURS.toMillis(1));
        testRollupIntervalMillis(ROLLUP.HOURLY, "UTC", "20161127T01", HOURS.toMillis(1));
        testRollupIntervalMillis(ROLLUP.DAILY, "UTC", "20161127T00", HOURS.toMillis(1) * 24);
        testRollupIntervalMillis(ROLLUP.WEEKLY, "UTC", "20161127T00", HOURS.toMillis(1) * 24 * 7);
        testRollupIntervalMillis(ROLLUP.MONTHLY, "UTC", "20161127T00", HOURS.toMillis(1) * 24 * 31);

        String tz = "America/Los_Angeles";
        testRollupIntervalMillis(ROLLUP.HOURLY, tz, "20161127T00", HOURS.toMillis(1));
        testRollupIntervalMillis(ROLLUP.HOURLY, tz, "20161127T01", HOURS.toMillis(1));
        testRollupIntervalMillis(ROLLUP.DAILY, tz, "20161127T00", HOURS.toMillis(1) * 24);
        testRollupIntervalMillis(ROLLUP.WEEKLY, tz, "20161127T00", HOURS.toMillis(1) * 24 * 7);
        // 30 days in November
        testRollupIntervalMillis(ROLLUP.MONTHLY, tz, "20161227T00", HOURS.toMillis(1) * 24 * 30);

        // extra 1 hour due to daylight savings time change
        testRollupIntervalMillis(ROLLUP.DAILY, tz, "20161107T00",
                HOURS.toMillis(1) * 24 + HOURS.toMillis(1));
        testRollupIntervalMillis(ROLLUP.WEEKLY, tz, "20161107T00",
                HOURS.toMillis(1) * 24 * 7 + HOURS.toMillis(1));
        // 31 days in October
        testRollupIntervalMillis(ROLLUP.MONTHLY, tz, "20161127T00",
                HOURS.toMillis(1) * 24 * 31 + HOURS.toMillis(1));
    }

    private static void testRollupCaptureTime(String tz) throws ParseException {
        testRollupCaptureTime(ROLLUP.HOURLY, tz, "20161127T0159", "20161127T0200");
        testRollupCaptureTime(ROLLUP.HOURLY, tz, "20161127T0200", "20161127T0200");
        testRollupCaptureTime(ROLLUP.HOURLY, tz, "20161127T0201", "20161127T0300");

        testRollupCaptureTime(ROLLUP.DAILY, tz, "20161126T2359", "20161127T0000");
        testRollupCaptureTime(ROLLUP.DAILY, tz, "20161127T0000", "20161127T0000");
        testRollupCaptureTime(ROLLUP.DAILY, tz, "20161127T0001", "20161128T0000");

        testRollupCaptureTime(ROLLUP.WEEKLY, tz, "20161126T2359", "20161127T0000", "20161120");
        testRollupCaptureTime(ROLLUP.WEEKLY, tz, "20161127T0000", "20161127T0000", "20161120");
        testRollupCaptureTime(ROLLUP.WEEKLY, tz, "20161127T0001", "20161204T0000", "20161120");

        testRollupCaptureTime(ROLLUP.WEEKLY, tz, "20161126T2359", "20161203T0000", "20161119");
        testRollupCaptureTime(ROLLUP.WEEKLY, tz, "20161127T0000", "20161203T0000", "20161119");
        testRollupCaptureTime(ROLLUP.WEEKLY, tz, "20161127T0001", "20161203T0000", "20161119");

        testRollupCaptureTime(ROLLUP.WEEKLY, tz, "20161126T2359", "20161128T0000", "20161121");
        testRollupCaptureTime(ROLLUP.WEEKLY, tz, "20161127T0000", "20161128T0000", "20161121");
        testRollupCaptureTime(ROLLUP.WEEKLY, tz, "20161127T0001", "20161128T0000", "20161121");

        testRollupCaptureTime(ROLLUP.MONTHLY, tz, "20161031T2359", "20161101T0000");
        testRollupCaptureTime(ROLLUP.MONTHLY, tz, "20161101T0000", "20161101T0000");
        testRollupCaptureTime(ROLLUP.MONTHLY, tz, "20161101T0001", "20161201T0000");
    }

    private static void testRollupCaptureTime(ROLLUP rollup, String timeZoneId,
            String captureTimeText, String expectedRollupCaptureTime,
            String... optionalBaseCaptureTime) throws ParseException {
        // given
        TimeZone timeZone = TimeZone.getTimeZone(timeZoneId);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmm");
        simpleDateFormat.setTimeZone(timeZone);
        Date captureTime = simpleDateFormat.parse(captureTimeText);
        String baseCaptureTime = rollup == ROLLUP.WEEKLY ? optionalBaseCaptureTime[0] : "";
        Function<Long, Long> rollupCaptureTimeFn =
                new RollupCaptureTimeFn(rollup, timeZone, baseCaptureTime);

        // when
        Long rollupCaptureTime = rollupCaptureTimeFn.apply(captureTime.getTime());

        // then
        assertThat(new Date(rollupCaptureTime))
                .isEqualTo(simpleDateFormat.parse(expectedRollupCaptureTime));
    }

    private static void testRollupIntervalMillis(ROLLUP rollup, String timeZoneId,
            String rollupCaptureTimeText, long expectedRollupIntervalMillis) throws ParseException {
        // given
        TimeZone timeZone = TimeZone.getTimeZone(timeZoneId);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd'T'HH");
        simpleDateFormat.setTimeZone(timeZone);
        Date captureTime = simpleDateFormat.parse(rollupCaptureTimeText);

        // when
        Long rollupIntervalMillis =
                ReportJsonService.getRollupIntervalMillis(rollup, timeZone, captureTime.getTime());

        // then
        assertThat(rollupIntervalMillis).isEqualTo(expectedRollupIntervalMillis);
    }
}
