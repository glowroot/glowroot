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

    private final List<CharSequence> csqs;
    private final int count;
    // fields to optimize sequential read of charAt
    private int lastCharAtIndex;
    private int charAtListIndex;
    private int charAtBaseIndex;
    // fields to optimize sequential read of getChars
    private int getCharsIndex;
    private int getCharsListIndex;
    private int getCharsBaseIndex;

    LargeCharSequence(List<CharSequence> csqs, int count) {
        this.csqs = csqs;
        this.count = count;
    }

    CharSequence escapeJson() {
        LargeStringBuilder sb = new LargeStringBuilder();
        for (CharSequence csq : csqs) {
            CharSequence jsonCsq = JsonCharSequence.escapeJson(csq);
            sb.csqs.add(jsonCsq);
            sb.count += jsonCsq.length();
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
        while (charAtListIndex < csqs.size()) {
            CharSequence s = csqs.get(charAtListIndex);
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
        for (CharSequence csq : csqs) {
            if (sb != null) {
                if (end < len + csq.length()) {
                    sb.append(csq.subSequence(0, end - len));
                    return sb.build();
                } else {
                    sb.append(csq);
                }
            } else if (start < len + csq.length()) {
                if (end < len + csq.length()) {
                    return csq.subSequence(start - len, end - len);
                } else {
                    sb = new LargeStringBuilder();
                    sb.append(csq.subSequence(start - len, csq.length()));
                }
            }
            len += csq.length();
        }
        throw new AssertionError("error in above logic");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(count);
        for (CharSequence csq : csqs) {
            sb.append(csq);
        }
        return sb.toString();
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        if (srcBegin == 0 || srcBegin != getCharsIndex) {
            // reset
            getCharsListIndex = 0;
            getCharsBaseIndex = 0;
            // advance
            while (getCharsListIndex < csqs.size()) {
                CharSequence s = csqs.get(getCharsListIndex);
                if (srcBegin < getCharsBaseIndex + s.length()) {
                    break;
                }
                getCharsListIndex++;
                getCharsBaseIndex += s.length();
            }
            if (getCharsListIndex == csqs.size()) {
                throw new AssertionError("error in above logic");
            }
            getCharsIndex = srcBegin;
        }
        CharSequence curr = csqs.get(getCharsListIndex);
        int currIndex = srcBegin - getCharsBaseIndex;
        int dstIndex = dstBegin;
        while (getCharsIndex < srcEnd) {
            if (currIndex == curr.length()) {
                if (getCharsListIndex == csqs.size() - 1) {
                    break;
                }
                getCharsListIndex++;
                getCharsBaseIndex += curr.length();
                curr = csqs.get(getCharsListIndex);
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
