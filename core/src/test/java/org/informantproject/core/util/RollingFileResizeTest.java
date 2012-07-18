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

import static org.fest.assertions.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RollingFileResizeTest {

    private File tempFile;
    private RollingFile rollingFile;

    @Before
    public void onBefore() throws IOException {
        tempFile = File.createTempFile("informant-test-", ".rolling.db");
        rollingFile = new RollingFile(tempFile, 2);
    }

    @After
    public void onAfter() throws IOException {
        rollingFile.shutdown();
        Files.delete(tempFile);
    }

    @Test
    public void shouldWrapAndResizeSmaller() throws Exception {
        shouldWrapAndResize(rollingFile, 1);
    }

    @Test
    public void shouldWrapAndResizeSameSize() throws Exception {
        shouldWrapAndResize(rollingFile, 2);
    }

    @Test
    public void shouldWrapAndResizeLarger() throws Exception {
        shouldWrapAndResize(rollingFile, 3);
    }

    private static void shouldWrapAndResize(RollingFile rollingFile, int newRollingSizeKb)
            throws Exception {

        // given
        // because of compression, use somewhat random text and loop until wrap occurs
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        String text = sb.toString();
        rollingFile.write(ByteStream.of(text));
        rollingFile.write(ByteStream.of(text));
        rollingFile.write(ByteStream.of(text));
        FileBlock block = rollingFile.write(ByteStream.of(text));
        // when
        rollingFile.resize(newRollingSizeKb);
        // then
        String text2 = RollingFileTest.toString(rollingFile.read(block));
        assertThat(text2).isEqualTo(text);
    }
}
