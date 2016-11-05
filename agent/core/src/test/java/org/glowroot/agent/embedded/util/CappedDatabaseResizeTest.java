/*
 * Copyright 2012-2016 the original author or authors.
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
package org.glowroot.agent.embedded.util;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import com.google.common.base.Charsets;
import com.google.common.base.Ticker;
import com.google.common.io.ByteSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CappedDatabaseResizeTest {

    private File tempFile;
    private CappedDatabase cappedDatabase;

    @Before
    public void onBefore() throws IOException {
        tempFile = File.createTempFile("glowroot-test-", ".capped.db");
        cappedDatabase = new CappedDatabase(tempFile, 2, Ticker.systemTicker());
    }

    @After
    public void onAfter() throws IOException {
        cappedDatabase.close();
        tempFile.delete();
    }

    @Test
    public void shouldWrapAndThenResizeSmaller() throws Exception {
        shouldWrapAndResize(1);
    }

    @Test
    public void shouldWrapAndThenResizeSameSize() throws Exception {
        shouldWrapAndResize(2);
    }

    @Test
    public void shouldWrapAndThenResizeLarger() throws Exception {
        shouldWrapAndResize(3);
    }

    @Test
    public void shouldResizeSmallerAndThenWrap() throws Exception {
        shouldResizeAndWrap(1);
    }

    @Test
    public void shouldResizeSameSizeAndThenWrap() throws Exception {
        shouldResizeAndWrap(2);
    }

    @Test
    public void shouldResizeLargerAndThenWrap() throws Exception {
        shouldResizeAndWrap(3);
    }

    private void shouldWrapAndResize(int newSizeKb) throws Exception {
        // when
        // because of compression, use somewhat random text and loop until wrap occurs
        String text = createRandomText();
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        long cappedId =
                cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        cappedDatabase.resize(newSizeKb);

        // then
        String text2 = cappedDatabase.read(cappedId).read();
        assertThat(text2).isEqualTo(text);

        // also test close and re-open
        cappedDatabase.close();
        cappedDatabase = new CappedDatabase(tempFile, 2, Ticker.systemTicker());
        text2 = cappedDatabase.read(cappedId).read();
        assertThat(text2).isEqualTo(text);
    }

    private void shouldResizeAndWrap(int newSizeKb) throws Exception {
        // when
        cappedDatabase.resize(newSizeKb);
        // because of compression, use somewhat random text and loop until wrap occurs
        String text = createRandomText();
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        long cappedId =
                cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");

        // then
        String text2 = cappedDatabase.read(cappedId).read();
        assertThat(text2).isEqualTo(text);

        // also test close and re-open
        cappedDatabase.close();
        cappedDatabase = new CappedDatabase(tempFile, 2, Ticker.systemTicker());
        text2 = cappedDatabase.read(cappedId).read();
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
