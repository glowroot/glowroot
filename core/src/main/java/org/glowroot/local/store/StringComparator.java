/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.local.store;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public enum StringComparator {

    BEGINS("like", "%s%%"),
    EQUALS("=", "%s"),
    ENDS("like", "%%%s"),
    CONTAINS("like", "%%%s%%"),
    NOT_CONTAINS("not like", "%%%s%%");

    private final String comparator;
    private final String parameterFormat;

    private StringComparator(String comparator, String parameterTemplate) {
        this.comparator = comparator;
        this.parameterFormat = parameterTemplate;
    }

    String formatParameter(String parameter) {
        return String.format(parameterFormat, parameter);
    }

    String getComparator() {
        return comparator;
    }
}
