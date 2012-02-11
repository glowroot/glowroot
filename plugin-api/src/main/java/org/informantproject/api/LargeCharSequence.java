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

import java.io.IOException;
import java.io.Reader;
import java.util.List;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LargeCharSequence implements CharSequence {

    private final List<CharSequence> strings;
    private final int count;
    // fields to optimize sequential read of charAt
    private int lastCharAtIndex;
    private int charAtListIndex;
    private int charAtBaseIndex;
    // fields to optimize sequential read of getChars
    private int getCharsIndex;
    private int getCharsListIndex;
    private int getCharsBaseIndex;

    LargeCharSequence(List<CharSequence> strings, int count) {
        this.strings = strings;
        this.count = count;
    }

    CharSequence toJson() {
        LargeStringBuilder sb = new LargeStringBuilder();
        for (CharSequence s : strings) {
            CharSequence js = JsonCharSequence.toJson(s);
            sb.strings.add(js);
            sb.count += js.length();
        }
        return sb.build();
    }

    public Reader asReader() {
        return new LargeCharSequenceReader();
    }

    public int length() {
        return count;
    }

    // optimized for sequential access
    public char charAt(int index) {
        if ((index < 0) || (index >= count)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        if (index < lastCharAtIndex) {
            // rewind
            charAtListIndex = 0;
            charAtBaseIndex = 0;
        }
        lastCharAtIndex = index;
        while (charAtListIndex < strings.size()) {
            CharSequence s = strings.get(charAtListIndex);
            if (index < charAtBaseIndex + s.length()) {
                return s.charAt(index - charAtBaseIndex);
            }
            charAtListIndex++;
            charAtBaseIndex += s.length();
        }
        throw new AssertionError("error in above logic");
    }

    public CharSequence subSequence(int start, int end) {
        if (start < 0) {
            throw new StringIndexOutOfBoundsException(start);
        }
        if (end > count) {
            throw new StringIndexOutOfBoundsException(end);
        }
        if (start > end) {
            throw new StringIndexOutOfBoundsException(end - start);
        }
        LargeStringBuilder sb = null;
        int len = 0;
        for (CharSequence s : strings) {
            if (sb != null) {
                if (end < len + s.length()) {
                    sb.append(s.subSequence(0, end - len));
                    return sb.build();
                } else {
                    sb.append(s);
                }
            } else if (start < len + s.length()) {
                if (end < len + s.length()) {
                    return s.subSequence(start - len, end - len);
                } else {
                    sb = new LargeStringBuilder();
                    sb.append(s.subSequence(start - len, s.length()));
                }
            }
            len += s.length();
        }
        throw new AssertionError("error in above logic");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(count);
        for (CharSequence s : strings) {
            sb.append(s);
        }
        return sb.toString();
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        if (srcBegin == 0 || srcBegin != getCharsIndex) {
            // reset
            getCharsListIndex = 0;
            getCharsBaseIndex = 0;
            // advance
            while (getCharsListIndex < strings.size()) {
                CharSequence s = strings.get(getCharsListIndex);
                if (srcBegin < getCharsBaseIndex + s.length()) {
                    break;
                }
                getCharsListIndex++;
                getCharsBaseIndex += s.length();
            }
            if (getCharsListIndex == strings.size()) {
                throw new AssertionError("error in above logic");
            }
            getCharsIndex = srcBegin;
        }
        CharSequence curr = strings.get(getCharsListIndex);
        int currIndex = srcBegin - getCharsBaseIndex;
        int dstIndex = dstBegin;
        while (getCharsIndex < srcEnd) {
            if (currIndex == curr.length()) {
                if (getCharsListIndex == strings.size() - 1) {
                    break;
                }
                getCharsListIndex++;
                getCharsBaseIndex += curr.length();
                curr = strings.get(getCharsListIndex);
                currIndex = 0;
            }
            int n = Math.min(srcEnd - getCharsIndex, curr.length() - currIndex);
            if (curr instanceof String) {
                ((String) curr).getChars(currIndex, currIndex + n, dst, dstIndex);
            } else if (curr instanceof StringBuilder) {
                ((StringBuilder) curr).getChars(currIndex, currIndex + n, dst, dstIndex);
            } else if (curr instanceof JsonCharSequence) {
                ((JsonCharSequence) curr).getChars(currIndex, currIndex + n, dst, dstIndex);
            } else if (curr instanceof LargeCharSequence) {
                ((LargeCharSequence) curr).getChars(currIndex, currIndex + n, dst, dstIndex);
            } else {
                // resort to for loop
                for (int i = 0; i < n; i++) {
                    dst[dstIndex + i] = curr.charAt(currIndex + i);
                }
            }
            currIndex += n;
            dstIndex += n;
            getCharsIndex += n;
        }
    }

    private class LargeCharSequenceReader extends Reader {

        private int currIndex;

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            int n = Math.min(count - currIndex, len);
            if (n == 0) {
                return -1;
            }
            getChars(currIndex, currIndex + n, cbuf, off);
            currIndex += n;
            return n;
        }

        @Override
        public void close() throws IOException {}
    }
}
