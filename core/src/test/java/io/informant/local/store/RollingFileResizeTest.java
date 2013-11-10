/*
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

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.Ticker;
import com.google.common.io.CharStreams;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RollingFileResizeTest {

    private File tempFile;
    private ScheduledExecutorService scheduledExecutor;
    private RollingFile rollingFile;

    @Before
    public void onBefore() throws IOException {
        tempFile = File.createTempFile("informant-test-", ".rolling.db");
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        rollingFile = new RollingFile(tempFile, 2, scheduledExecutor, Ticker.systemTicker());
    }

    @After
    public void onAfter() throws IOException {
        scheduledExecutor.shutdownNow();
        rollingFile.close();
        tempFile.delete();
    }

    @Test
    public void shouldWrapAndThenResizeSmaller() throws Exception {
        shouldWrapAndResize(rollingFile, 1);
    }

    @Test
    public void shouldWrapAndThenResizeSameSize() throws Exception {
        shouldWrapAndResize(rollingFile, 2);
    }

    @Test
    public void shouldWrapAndThenResizeLarger() throws Exception {
        shouldWrapAndResize(rollingFile, 3);
    }

    @Test
    public void shouldResizeSmallerAndThenWrap() throws Exception {
        shouldResizeAndWrap(rollingFile, 1);
    }

    @Test
    public void shouldResizeSameSizeAndThenWrap() throws Exception {
        shouldResizeAndWrap(rollingFile, 2);
    }

    @Test
    public void shouldResizeLargerAndThenWrap() throws Exception {
        shouldResizeAndWrap(rollingFile, 3);
    }

    private void shouldWrapAndResize(RollingFile rollingFile, int newRollingSizeKb)
            throws Exception {

        // given
        // when
        // because of compression, use somewhat random text and loop until wrap occurs
        String text = createRandomText();
        rollingFile.write(CharStreams.asCharSource(text));
        rollingFile.write(CharStreams.asCharSource(text));
        rollingFile.write(CharStreams.asCharSource(text));
        FileBlock block = rollingFile.write(CharStreams.asCharSource(text));
        rollingFile.resize(newRollingSizeKb);
        // then
        String text2 = rollingFile.read(block, "").read();
        assertThat(text2).isEqualTo(text);

        // also test close and re-open
        rollingFile.close();
        rollingFile = new RollingFile(tempFile, 2, scheduledExecutor, Ticker.systemTicker());
        text2 = rollingFile.read(block, "").read();
        assertThat(text2).isEqualTo(text);
    }

    private void shouldResizeAndWrap(RollingFile rollingFile, int newRollingSizeKb)
            throws Exception {

        // given
        // when
        rollingFile.resize(newRollingSizeKb);
        // because of compression, use somewhat random text and loop until wrap occurs
        String text = createRandomText();
        rollingFile.write(CharStreams.asCharSource(text));
        rollingFile.write(CharStreams.asCharSource(text));
        rollingFile.write(CharStreams.asCharSource(text));
        FileBlock block = rollingFile.write(CharStreams.asCharSource(text));
        // then
        String text2 = rollingFile.read(block, "").read();
        assertThat(text2).isEqualTo(text);

        // also test close and re-open
        rollingFile.close();
        rollingFile = new RollingFile(tempFile, 2, scheduledExecutor, Ticker.systemTicker());
        text2 = rollingFile.read(block, "").read();
        assertThat(text2).isEqualTo(text);
    }

    private String createRandomText() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        String text = sb.toString();
        return text;
    }
}
