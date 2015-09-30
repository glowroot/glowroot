/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.common.live;

import java.util.Locale;

import org.checkerframework.checker.tainting.qual.Untainted;

public enum StringComparator {

    BEGINS("like", "%s%%") {
        @Override
        public boolean matches(String text, String partial) {
            return upper(text).startsWith(upper(partial));
        }
    },
    EQUALS("=", "%s") {
        @Override
        public boolean matches(String text, String partial) {
            return text.equalsIgnoreCase(partial);
        }
    },
    ENDS("like", "%%%s") {
        @Override
        public boolean matches(String text, String partial) {
            return upper(text).endsWith(upper(partial));
        }
    },
    CONTAINS("like", "%%%s%%") {
        @Override
        public boolean matches(String text, String partial) {
            return upper(text).contains(upper(partial));
        }
    },
    NOT_CONTAINS("not like", "%%%s%%") {
        @Override
        public boolean matches(String text, String partial) {
            return !upper(text).contains(upper(partial));
        }
    };

    private final @Untainted String comparator;
    private final String parameterFormat;

    private StringComparator(@Untainted String comparator, String parameterTemplate) {
        this.comparator = comparator;
        this.parameterFormat = parameterTemplate;
    }

    public String formatParameter(String parameter) {
        return String.format(parameterFormat, upper(parameter));
    }

    @Untainted
    public String getComparator() {
        return comparator;
    }

    public abstract boolean matches(String text, String partial);

    private static String upper(String str) {
        return str.toUpperCase(Locale.ENGLISH);
    }
}
