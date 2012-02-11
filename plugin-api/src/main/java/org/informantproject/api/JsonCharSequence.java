/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.api;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JsonCharSequence implements CharSequence {

    private final CharSequence s;
    private final int expandedSize;
    private StringBuilder expanded;

    private JsonCharSequence(CharSequence s, int expandedSize) {
        this.s = s;
        this.expandedSize = expandedSize;
    }

    public int length() {
        return expandedSize;
    }

    public char charAt(int index) {
        if (expanded == null) {
            expanded = jsonExpandedString(s);
        }
        return expanded.charAt(index);
    }

    public CharSequence subSequence(int start, int end) {
        if (expanded == null) {
            expanded = jsonExpandedString(s);
        }
        return expanded.subSequence(start, end);
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        if (expanded == null) {
            expanded = jsonExpandedString(s);
        }
        expanded.getChars(srcBegin, srcEnd, dst, dstBegin);
    }

    public static CharSequence toJson(CharSequence s) {
        if (s instanceof LargeCharSequence) {
            return ((LargeCharSequence) s).toJson();
        }
        int expandedSize = jsonExpandedSize(s);
        if (expandedSize == s.length()) {
            return s;
        } else {
            return new JsonCharSequence(s, expandedSize);
        }
    }

    private static int jsonExpandedSize(CharSequence s) {
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c == '\t' || c == '\b' || c == '\n' || c == '\r'
                    || c == '\f') {
                len += 2;
            } else {
                len++;
            }
        }
        return len;
    }

    private static StringBuilder jsonExpandedString(CharSequence s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c == '\t' || c == '\b' || c == '\n' || c == '\r'
                    || c == '\f') {
                sb.append('\\');
                sb.append(c);
            } else {
                sb.append(c);
            }
        }
        return sb;
    }
}
