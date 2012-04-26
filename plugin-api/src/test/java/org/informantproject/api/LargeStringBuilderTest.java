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

import java.util.Random;

import org.junit.Test;

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
    public void shouldReadLargeString() {
        // given
        final int nChunks = 100;
        final int chunkSize = 100;
        LargeStringBuilder lsb = new LargeStringBuilder();
        StringBuilder sb = new StringBuilder();
        // when
        for (int i = 0; i < nChunks; i++) {
            String randomString = generateRandomString(chunkSize);
            lsb.append(randomString);
            sb.append(randomString);
        }
        // then
        assertThat(lsb.toString(), is(sb.toString()));
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
