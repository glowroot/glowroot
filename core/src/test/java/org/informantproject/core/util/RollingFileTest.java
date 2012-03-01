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
package org.informantproject.core.util;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.util.Random;

import org.informantproject.core.util.FileBlock;
import org.informantproject.core.util.RollingFile;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RollingFileTest {

    @Test
    public void shouldWrite() throws Exception {
        // given
        File file = File.createTempFile("informant-unit-test-", ".rolling.db");
        RollingFile rollingFile = new RollingFile(file, 1);
        String text = "0123456789";
        // when
        FileBlock block = rollingFile.write(text);
        // then
        String text2 = rollingFile.read(block);
        assertThat(text2, is(text));
    }

    @Test
    public void shouldWrap() throws Exception {
        // given
        File file = File.createTempFile("informant-unit-test-", ".rolling.db");
        RollingFile rollingFile = new RollingFile(file, 1);
        // because of compression, use somewhat random text and loop until wrap occurs
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        String text = sb.toString();
        rollingFile.write(text);
        // when
        FileBlock block = rollingFile.write(text);
        // then
        String text2 = rollingFile.read(block);
        assertThat(text2, is(text));
    }

    @Test
    public void shouldWrapAndKeepGoing() throws Exception {
        // given
        File file = File.createTempFile("informant-unit-test-", ".rolling.db");
        RollingFile rollingFile = new RollingFile(file, 1);
        // because of compression, use somewhat random text and loop until wrap occurs
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        String text = sb.toString();
        rollingFile.write(text);
        rollingFile.write(text);
        // when
        FileBlock block = rollingFile.write(text);
        // then
        String text2 = rollingFile.read(block);
        assertThat(text2, is(text));
    }

    @Test
    public void shouldWrapAndResizeSmaller() throws Exception {
        shouldWrapAndResize(1);
    }

    @Test
    public void shouldWrapAndResizeSameSize() throws Exception {
        shouldWrapAndResize(2);
    }

    @Test
    public void shouldWrapAndResizeLarger() throws Exception {
        shouldWrapAndResize(3);
    }

    private static void shouldWrapAndResize(int newRollingSizeKb) throws Exception {
        // given
        File file = File.createTempFile("informant-unit-test-", ".rolling.db");
        RollingFile rollingFile = new RollingFile(file, 2);
        // because of compression, use somewhat random text and loop until wrap occurs
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        String text = sb.toString();
        rollingFile.write(text);
        rollingFile.write(text);
        rollingFile.write(text);
        FileBlock block = rollingFile.write(text);
        // when
        rollingFile.resize(newRollingSizeKb);
        // then
        String text2 = rollingFile.read(block);
        assertThat(text2, is(text));
    }
}
