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
import java.io.Reader;
import java.util.Random;

import com.google.common.base.Charsets;
import com.google.common.base.Ticker;
import com.google.common.io.ByteSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.assertj.core.api.Assertions.assertThat;

public class CappedDatabaseTest {

    private File tempFile;
    private CappedDatabase cappedDatabase;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void onBefore() throws IOException {
        tempFile = File.createTempFile("glowroot-test-", ".capped.db");
        cappedDatabase = new CappedDatabase(tempFile, 1, Ticker.systemTicker());
    }

    @After
    public void onAfter() throws IOException {
        cappedDatabase.close();
        tempFile.delete();
    }

    @Test
    public void shouldWrite() throws Exception {
        // given
        String text = "0123456789";
        // when
        long cappedId =
                cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        // then
        String text2 = cappedDatabase.read(cappedId).read();
        assertThat(text2).isEqualTo(text);
    }

    @Test
    public void shouldReadOneByteAtATime() throws Exception {
        // given
        String text = "0123456789";

        // when
        long cappedId =
                cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");

        // then
        Reader in = cappedDatabase.read(cappedId).openStream();
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
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");

        // when
        long cappedId =
                cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");

        // then
        String text2 = cappedDatabase.read(cappedId).read();
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
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");

        // when
        long cappedId =
                cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");

        // then
        String text2 = cappedDatabase.read(cappedId).read();
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
        long cappedId =
                cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");

        // when
        cappedDatabase.write(ByteSource.wrap(text.getBytes(Charsets.UTF_8)), "test");

        // then
        String exceptionClassName = null;
        try {
            cappedDatabase.read(cappedId).read();
        } catch (Exception e) {
            exceptionClassName = e.getClass().getName();
        }
        assertThat(exceptionClassName).isEqualTo("org.glowroot.agent.embedded.util.CappedDatabase"
                + "$CappedBlockRolledOverMidReadException");
    }
}
