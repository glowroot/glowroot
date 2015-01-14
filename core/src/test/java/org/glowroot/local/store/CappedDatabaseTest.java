/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.local.store;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.io.CharSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.Ticker;

import static org.assertj.core.api.Assertions.assertThat;

public class CappedDatabaseTest {

    private File tempFile;
    private ScheduledExecutorService scheduledExecutor;
    private CappedDatabase cappedDatabase;

    @Before
    public void onBefore() throws IOException {
        tempFile = File.createTempFile("glowroot-test-", ".capped.db");
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        cappedDatabase = new CappedDatabase(tempFile, 1, scheduledExecutor, Ticker.systemTicker());
    }

    @After
    public void onAfter() throws IOException {
        scheduledExecutor.shutdownNow();
        cappedDatabase.close();
        tempFile.delete();
    }

    @Test
    public void shouldWrite() throws Exception {
        // given
        String text = "0123456789";
        // when
        long cappedId = cappedDatabase.write(CharSource.wrap(text));
        // then
        String text2 = cappedDatabase.read(cappedId, "").read();
        assertThat(text2).isEqualTo(text);
    }

    @Test
    public void shouldReadOneByteAtATime() throws Exception {
        // given
        String text = "0123456789";
        // when
        long cappedId = cappedDatabase.write(CharSource.wrap(text));
        // then
        Reader in = cappedDatabase.read(cappedId, "").openStream();
        assertThat((char) in.read()).isEqualTo('0');
        assertThat((char) in.read()).isEqualTo('1');
        assertThat((char) in.read()).isEqualTo('2');
        assertThat((char) in.read()).isEqualTo('3');
        assertThat((char) in.read()).isEqualTo('4');
        assertThat((char) in.read()).isEqualTo('5');
        assertThat((char) in.read()).isEqualTo('6');
        assertThat((char) in.read()).isEqualTo('7');
        assertThat((char) in.read()).isEqualTo('8');
        assertThat((char) in.read()).isEqualTo('9');
        assertThat(in.read()).isEqualTo(-1);
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
        cappedDatabase.write(CharSource.wrap(text));
        // when
        long cappedId = cappedDatabase.write(CharSource.wrap(text));
        // then
        String text2 = cappedDatabase.read(cappedId, "").read();
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
        cappedDatabase.write(CharSource.wrap(text));
        cappedDatabase.write(CharSource.wrap(text));
        // when
        long cappedId = cappedDatabase.write(CharSource.wrap(text));
        // then
        String text2 = cappedDatabase.read(cappedId, "").read();
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
        long cappedId = cappedDatabase.write(CharSource.wrap(text));
        cappedDatabase.write(CharSource.wrap(text));
        // when
        cappedDatabase.write(CharSource.wrap(text));
        // then
        // for now, overwritten blocks return empty byte array when read
        String text2 = cappedDatabase.read(cappedId, "").read();
        assertThat(text2).isEqualTo("");
    }
}
