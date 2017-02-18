/*
 * Copyright 2017 the original author or authors.
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

import java.util.Locale;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class FormattingTest {

    @Test
    public void testFormattingInEnglishLocale() {
        testFormattingInLocale(new Locale("en"), ',', '.');
    }

    @Test
    public void testFormattingInUkraineLocale() {
        // unicode 160 is non-breaking space
        testFormattingInLocale(new Locale("uk"), (char) 160, ',');
    }

    @Test
    public void testFormattingBytesInEnglishLocale() {
        testFormattingBytesInLocale(new Locale("en"), '.');
    }

    @Test
    public void testFormattingBytesInUkraineLocale() {
        testFormattingBytesInLocale(new Locale("uk"), ',');
    }

    public void testFormattingBytesInLocale(Locale locale, char ds) {
        assertThat(Formatting.formatBytes(0, locale)).isEqualTo("0");
        assertThat(Formatting.formatBytes(1, locale)).isEqualTo("1 byte");
        assertThat(Formatting.formatBytes(2, locale)).isEqualTo("2 bytes");
        assertThat(Formatting.formatBytes(10, locale)).isEqualTo("10 bytes");
        assertThat(Formatting.formatBytes(100, locale)).isEqualTo("100 bytes");
        assertThat(Formatting.formatBytes(1023, locale)).isEqualTo("1023 bytes");
        assertThat(Formatting.formatBytes(1024, locale)).isEqualTo("1" + ds + "0 KB");
        assertThat(Formatting.formatBytes(1500, locale)).isEqualTo("1" + ds + "5 KB");
        assertThat(Formatting.formatBytes(2047, locale)).isEqualTo("2" + ds + "0 KB");
        assertThat(Formatting.formatBytes(1024 * 1024, locale)).isEqualTo("1" + ds + "0 MB");
        assertThat(Formatting.formatBytes(1024 * 1024 * 1024, locale)).isEqualTo("1" + ds + "0 GB");
    }

    private static void testFormattingInLocale(Locale locale, char ts, char ds) {
        assertThat(Formatting.displaySixDigitsOfPrecision(3, locale))
                .isEqualTo("3");
        assertThat(Formatting.displaySixDigitsOfPrecision(333333, locale))
                .isEqualTo("333" + ts + "333");
        assertThat(Formatting.displaySixDigitsOfPrecision(3333333, locale))
                .isEqualTo("3" + ts + "333" + ts + "333");

        assertThat(Formatting.displaySixDigitsOfPrecision(3.3, locale))
                .isEqualTo("3" + ds + "3");
        assertThat(Formatting.displaySixDigitsOfPrecision(3333.3, locale))
                .isEqualTo("3" + ts + "333" + ds + "3");
        assertThat(Formatting.displaySixDigitsOfPrecision(3333333.3, locale))
                .isEqualTo("3" + ts + "333" + ts + "333");

        assertThat(Formatting.displaySixDigitsOfPrecision(3.33333, locale))
                .isEqualTo("3" + ds + "33333");
        assertThat(Formatting.displaySixDigitsOfPrecision(3.333333, locale))
                .isEqualTo("3" + ds + "33333");
        assertThat(Formatting.displaySixDigitsOfPrecision(3.3333333, locale))
                .isEqualTo("3" + ds + "33333");

        assertThat(Formatting.displaySixDigitsOfPrecision(0.333333, locale))
                .isEqualTo("0" + ds + "333333");
        assertThat(Formatting.displaySixDigitsOfPrecision(0.3333333, locale))
                .isEqualTo("0" + ds + "333333");
        assertThat(Formatting.displaySixDigitsOfPrecision(0.33333333, locale))
                .isEqualTo("0" + ds + "333333");

        assertThat(Formatting.displaySixDigitsOfPrecision(0.0333333, locale))
                .isEqualTo("0" + ds + "0333333");
        assertThat(Formatting.displaySixDigitsOfPrecision(0.03333333, locale))
                .isEqualTo("0" + ds + "0333333");
        assertThat(Formatting.displaySixDigitsOfPrecision(0.033333333, locale))
                .isEqualTo("0" + ds + "0333333");

        assertThat(Formatting.displaySixDigitsOfPrecision(0.000000333333, locale))
                .isEqualTo("0" + ds + "000000333333");
        assertThat(Formatting.displaySixDigitsOfPrecision(0.0000003333333, locale))
                .isEqualTo("0" + ds + "000000333333");
        assertThat(Formatting.displaySixDigitsOfPrecision(0.00000033333333, locale))
                .isEqualTo("0" + ds + "000000333333");
    }
}
