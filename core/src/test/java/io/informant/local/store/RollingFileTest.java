/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.store;

import static org.fest.assertions.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.io.CharStreams;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RollingFileTest {

    private File tempFile;
    private RollingFile rollingFile;

    @Before
    public void onBefore() throws IOException {
        tempFile = File.createTempFile("informant-test-", ".rolling.db");
        rollingFile = new RollingFile(tempFile, 1);
    }

    @After
    public void onAfter() throws IOException {
        rollingFile.close();
        tempFile.delete();
    }

    @Test
    public void shouldWrite() throws Exception {
        // given
        String text = "0123456789";
        // when
        FileBlock block = rollingFile.write(CharStreams.asCharSource(text));
        // then
        String text2 = rollingFile.read(block, "").read();
        assertThat(text2).isEqualTo(text);
    }

    @Test
    public void shouldWrap() throws Exception {
        // given
        // use random text so that the lzf compressed text is also large and forces wrapping
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        String text = sb.toString();
        rollingFile.write(CharStreams.asCharSource(text));
        // when
        FileBlock block = rollingFile.write(CharStreams.asCharSource(text));
        // then
        String text2 = rollingFile.read(block, "").read();
        assertThat(text2).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndKeepGoing() throws Exception {
        // given
        // use random text so that the lzf compressed text is also large and forces wrapping
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        String text = sb.toString();
        rollingFile.write(CharStreams.asCharSource(text));
        rollingFile.write(CharStreams.asCharSource(text));
        // when
        FileBlock block = rollingFile.write(CharStreams.asCharSource(text));
        // then
        String text2 = rollingFile.read(block, "").read();
        assertThat(text2).isEqualTo(text);
    }

    @Test
    public void shouldWrapOverOldBlocks() throws Exception {
        // given
        // use random text so that the lzf compressed text is also large and forces wrapping
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        String text = sb.toString();
        FileBlock block = rollingFile.write(CharStreams.asCharSource(text));
        rollingFile.write(CharStreams.asCharSource(text));
        // when
        rollingFile.write(CharStreams.asCharSource(text));
        // then
        // for now, overwritten blocks return empty byte array when read
        String text2 = rollingFile.read(block, "").read();
        assertThat(text2).isEqualTo("");
    }
}
