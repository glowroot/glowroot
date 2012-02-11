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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.util.Random;

import org.junit.Test;

import com.google.common.io.CharStreams;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LargeStringBuilderTest {

    private static final Random random = new Random();

    @Test
    public void shouldBuildSmallString() {
        // given
        StringBuilder sb1 = new StringBuilder();
        LargeStringBuilder sb2 = new LargeStringBuilder();
        for (int i = 0; i < 10; i++) {
            String s = generateRandomString(10);
            sb1.append(s);
            sb2.append(s);
        }
        // then
        assertThat(sb2.build().toString(), is(sb1.toString()));
    }

    @Test
    public void shouldReadIntoCharArray() throws IOException {
        // given
        CharSequence s = getLargeCharSequence(10, 100);
        // when
        char[] cbuf1 = new char[s.length() / 2];
        Reader in = ((LargeCharSequence) s).asReader();
        int n1 = in.read(cbuf1);
        String t1 = new String(cbuf1, 0, n1);
        char[] cbuf2 = new char[s.length() / 2];
        int n2 = in.read(cbuf2);
        String t2 = new String(cbuf2, 0, n2);
        // then
        assertThat(t1 + t2, is(s.toString()));
    }

    @Test
    public void shouldReadLargeString() throws IOException {
        // given
        CharSequence s = getLargeCharSequence(100, 100);
        // when
        String t = CharStreams.toString(((LargeCharSequence) s).asReader());
        // then
        assertThat(t, is(s.toString()));
    }

    private CharSequence getLargeCharSequence(int nChunks, int chunkSize) {
        LargeStringBuilder sb = new LargeStringBuilder();
        for (int i = 0; i < nChunks; i++) {
            sb.append(generateRandomString(chunkSize));
        }
        CharSequence s = sb.build();
        return s;
    }

    public String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (random.nextInt(6) == 0) {
                // on average, one of six characters will be a space
                sb.append(' ');
            } else {
                // the rest will be random lowercase characters
                sb.append((char) ('a' + random.nextInt(26)));
            }
        }
        return sb.toString();
    }
}
