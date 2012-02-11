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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class CharSequenceReader extends Reader {

    private final CharSequence s;
    private int index;

    public CharSequenceReader(CharSequence s) {
        this.s = s;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        int n = Math.min(s.length() - index, len);
        if (n == 0) {
            return -1;
        }
        if (s instanceof String) {
            ((String) s).getChars(index, index + n, cbuf, off);
        } else if (s instanceof StringBuilder) {
            ((StringBuilder) s).getChars(index, index + n, cbuf, off);
        } else if (s instanceof JsonCharSequence) {
            ((JsonCharSequence) s).getChars(index, index + n, cbuf, off);
        } else if (s instanceof LargeCharSequence) {
            ((LargeCharSequence) s).getChars(index, index + n, cbuf, off);
        } else {
            // resort to for loop
            for (int i = 0; i < n; i++) {
                cbuf[off + i] = s.charAt(index + i);
            }
        }
        index += n;
        return n;
    }

    @Override
    public void close() throws IOException {}
}
