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

    final List<CharSequence> csqs = new ArrayList<CharSequence>();
    private final StringBuilder curr = new StringBuilder(BLOCK_SIZE);
    int count;

    // part of the contract is that char sequences should not be modified after appending
    // this is not an issue with immutable char sequences like String and LargeCharSequence (below)
    // but is an issue with mutable char sequences like StringBuilder and StringBuffer
    public LargeStringBuilder append(CharSequence csq) {
        if (csq.length() >= LARGE_THRESHOLD) {
            flush();
            csqs.add(csq);
        } else {
            if (curr.length() + csq.length() > BLOCK_SIZE) {
                csqs.add(curr.toString());
                curr.setLength(0);
            }
            curr.append(csq);
        }
        count += csq.length();
        return this;
    }

    public Appendable append(CharSequence csq, int start, int end) throws IOException {
        // this is not currently needed
        throw new UnsupportedOperationException();
    }

    public Appendable append(char c) throws IOException {
        if (curr.length() + 1 > BLOCK_SIZE) {
            csqs.add(curr.toString());
            curr.setLength(0);
        }
        curr.append(c);
        count++;
        return this;
    }

    public CharSequence build() {
        flush();
        return new LargeCharSequence(csqs, count);
    }

    private void flush() {
        if (curr != null) {
            csqs.add(curr.toString());
            curr.setLength(0);
        }
    }

    @Override
    public String toString() {
        return build().toString();
    }
}
