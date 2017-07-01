/*
 * Copyright 2014-2017 the original author or authors.
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

import java.util.List;

import javax.management.ObjectName;

import com.google.common.collect.Lists;

class ObjectNames {

    static List<String> getPropertyValues(ObjectName objectName) {
        return new ObjectNameParser(objectName).getPropertyValues();
    }

    private static class ObjectNameParser {

        private final char[] chars;
        private int index;
        private boolean inValue;
        private boolean inQuotedValue;
        private final StringBuilder currValue = new StringBuilder();
        private final List<String> values = Lists.newArrayList();

        private ObjectNameParser(ObjectName objectName) {
            chars = objectName.getKeyPropertyListString().toCharArray();
        }

        private List<String> getPropertyValues() {
            while (index < chars.length) {
                readNextChar();
            }
            if (inValue) {
                // add the last value
                values.add(currValue.toString());
            }
            return values;
        }

        private void readNextChar() {
            char c = chars[index++];
            if (isStartOfValue(c)) {
                startValue();
            } else if (isEndOfValue(c)) {
                endValue();
            } else if (inQuotedValue && c == '\\') {
                char d = chars[index++];
                if (d == 'n') {
                    d = '\n';
                }
                currValue.append(d);
            } else if (inValue) {
                currValue.append(c);
            }
        }

        private boolean isStartOfValue(char c) {
            return !inValue && c == '=';
        }

        private void startValue() {
            inValue = true;
            if (index < chars.length && chars[index] == '"') {
                inQuotedValue = true;
                index++;
            }
        }

        private boolean isEndOfValue(char c) {
            return isEndOfQuotedValue(c) || isEndOfNonQuotedValue(c);
        }

        private void endValue() {
            values.add(currValue.toString());
            inValue = false;
            inQuotedValue = false;
            currValue.setLength(0);
        }

        private boolean isEndOfQuotedValue(char c) {
            return inQuotedValue && c == '"';
        }

        private boolean isEndOfNonQuotedValue(char c) {
            return inValue && !inQuotedValue && c == ',';
        }
    }

    private ObjectNames() {}
}
