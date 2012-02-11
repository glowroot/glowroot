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
import java.util.ArrayList;
import java.util.List;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// not thread safe
public class LargeStringBuilder implements Appendable {

    private static final int LARGE_THRESHOLD = 100;
    private static final int BLOCK_SIZE = 200;

    final List<CharSequence> strings = new ArrayList<CharSequence>();
    private StringBuilder curr;
    int count;

    // part of the contract is that char sequences should not be modified after appending
    // this is not an issue with immutable char sequences like String and LargeCharSequence (below)
    // but is an issue with mutable char sequences like StringBuilder and StringBuffer
    public LargeStringBuilder append(CharSequence s) {
        if (s.length() >= LARGE_THRESHOLD) {
            if (curr != null) {
                strings.add(curr);
                curr = null;
            }
            strings.add(s);
        } else {
            if (curr == null) {
                curr = new StringBuilder(BLOCK_SIZE);
            }
            if (curr.length() + s.length() > BLOCK_SIZE) {
                strings.add(curr);
                curr = new StringBuilder(BLOCK_SIZE);
            }
            curr.append(s);
        }
        count += s.length();
        return this;
    }

    public Appendable append(CharSequence csq, int start, int end) throws IOException {
        throw new UnsupportedOperationException();
    }

    public Appendable append(char c) throws IOException {
        if (curr == null) {
            curr = new StringBuilder(BLOCK_SIZE);
        }
        if (curr.length() + 1 > BLOCK_SIZE) {
            strings.add(curr);
            curr = new StringBuilder(BLOCK_SIZE);
        }
        curr.append(c);
        count++;
        return this;
    }

    public CharSequence build() {
        if (curr != null) {
            strings.add(curr);
            curr = null;
        }
        return new LargeCharSequence(strings, count);
    }
}
