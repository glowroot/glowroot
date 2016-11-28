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
package org.glowroot.common.repo;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.Test;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;

public class UtilsTest {

    @Test
    public void testUTC() throws ParseException {
        // given
        TimeZone timeZone = TimeZone.getTimeZone("UTC");
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd'T'HH");
        simpleDateFormat.setTimeZone(timeZone);
        Date date = simpleDateFormat.parse("20161126T02");

        // when
        long rollupCaptureTime =
                Utils.getRollupCaptureTime(date.getTime(), DAYS.toMillis(1), timeZone);

        // then
        assertThat(simpleDateFormat.format(new Date(rollupCaptureTime))).isEqualTo("20161127T00");
    }

    @Test
    public void testLosAngeles() throws ParseException {
        // given
        TimeZone timeZone = TimeZone.getTimeZone("America/Los_Angeles");
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd'T'HH");
        simpleDateFormat.setTimeZone(timeZone);
        Date date = simpleDateFormat.parse("20161126T02");

        // when
        long rollupCaptureTime =
                Utils.getRollupCaptureTime(date.getTime(), DAYS.toMillis(1), timeZone);

        // then
        assertThat(simpleDateFormat.format(new Date(rollupCaptureTime))).isEqualTo("20161127T00");
    }

    @Test
    public void testNewYork() throws ParseException {
        // given
        TimeZone timeZone = TimeZone.getTimeZone("America/New_York");
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd'T'HH");
        simpleDateFormat.setTimeZone(timeZone);
        Date date = simpleDateFormat.parse("20161126T02");

        // when
        long rollupCaptureTime =
                Utils.getRollupCaptureTime(date.getTime(), DAYS.toMillis(1), timeZone);

        // then
        assertThat(simpleDateFormat.format(new Date(rollupCaptureTime))).isEqualTo("20161127T00");
    }
}
