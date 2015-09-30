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
package org.glowroot.agent.core.model;

import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TraceUniqueIdTest {

    @Test
    public void testYear2014() throws ParseException {
        // given
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
        Date d = formatter.parse("1/1/2014");
        long time = d.getTime();
        // when
        String twelveDigitHex = TraceUniqueId.twelveDigitHex(time);
        // then
        assertThat(BigInteger.valueOf(time).toString(16).length()).isEqualTo(11);
        assertThat(twelveDigitHex.length()).isEqualTo(12);
    }

    @Test
    public void testYear2528() throws ParseException {
        // given
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
        Date d = formatter.parse("1/1/2528");
        long time = d.getTime();
        // when
        String twelveDigitHex = TraceUniqueId.twelveDigitHex(time);
        // then
        assertThat(BigInteger.valueOf(time).toString(16).length()).isEqualTo(12);
        assertThat(twelveDigitHex.length()).isEqualTo(12);
    }

    @Test
    public void testYear10890() throws ParseException {
        // given
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
        Date d = formatter.parse("1/1/10890");
        long time = d.getTime();
        // when
        String twelveDigitHex = TraceUniqueId.twelveDigitHex(time);
        // then
        assertThat(BigInteger.valueOf(time).toString(16).length()).isEqualTo(13);
        assertThat(twelveDigitHex.length()).isEqualTo(12);
    }

    @Test
    public void testYear2004() throws ParseException {
        // given
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
        Date d = formatter.parse("1/1/2004");
        long time = d.getTime();
        // when
        String twelveDigitHex = TraceUniqueId.twelveDigitHex(time);
        // then
        assertThat(BigInteger.valueOf(time).toString(16).length()).isEqualTo(10);
        assertThat(twelveDigitHex.length()).isEqualTo(12);
    }
}
