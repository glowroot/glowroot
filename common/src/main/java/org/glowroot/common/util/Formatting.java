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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

import com.google.common.annotations.VisibleForTesting;

public class Formatting {

    private Formatting() {}

    public static String displaySixDigitsOfPrecision(double value) {
        return displaySixDigitsOfPrecision(value, Locale.getDefault());
    }

    public static String formatBytes(long bytes) {
        return formatBytes(bytes, Locale.getDefault());
    }

    // this mimics the javascript function of same name in gauge-values.js
    @VisibleForTesting
    static String displaySixDigitsOfPrecision(double value, Locale locale) {
        NumberFormat format = NumberFormat.getInstance(locale);
        format.setMaximumFractionDigits(20);
        if (value < 1000000) {
            return format.format(
                    BigDecimal.valueOf(value).round(new MathContext(6, RoundingMode.HALF_UP)));
        } else {
            return format.format(Math.round(value));
        }
    }

    // this mimics the javascript function of same name in handlebars-rendering.js
    @VisibleForTesting
    static String formatBytes(long bytes, Locale locale) {
        if (bytes == 0) {
            // no unit needed
            return "0";
        }
        if (bytes == 1) {
            // special case for singular
            return "1 byte";
        }
        String[] units = new String[] {"bytes", "KB", "MB", "GB", "TB", "PB"};
        int number = (int) Math.floor(Math.log(bytes) / Math.log(1024));
        double num = bytes / Math.pow(1024, number);
        if (number == 0) {
            return Math.round(num) + " bytes";
        } else {
            NumberFormat format = NumberFormat.getInstance(locale);
            format.setGroupingUsed(false);
            format.setRoundingMode(RoundingMode.HALF_UP);
            format.setMinimumFractionDigits(1);
            format.setMaximumFractionDigits(1);
            return format.format(num) + ' ' + units[number];
        }
    }
}
