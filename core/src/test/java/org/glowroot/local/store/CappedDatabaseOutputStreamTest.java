/*
 * Copyright 2012-2014 the original author or authors.
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
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.Ticker;

import static org.assertj.core.api.Assertions.assertThat;

public class CappedDatabaseOutputStreamTest {

    private File tempFile;
    private ScheduledExecutorService scheduledExecutor;
    private CappedDatabaseOutputStream cappedOut;
    private RandomAccessFile in;

    @Before
    public void onBefore() throws IOException {
        tempFile = File.createTempFile("glowroot-test-", ".capped.txt");
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        cappedOut = CappedDatabaseOutputStream.create(tempFile, 1, scheduledExecutor,
                Ticker.systemTicker());
        in = new RandomAccessFile(tempFile, "r");
    }

    @After
    public void onAfter() throws IOException {
        scheduledExecutor.shutdownNow();
        cappedOut.close();
        in.close();
        tempFile.delete();
    }

    @Test
    public void shouldWrite() throws IOException {
        // given
        Writer out = new OutputStreamWriter(cappedOut);
        String text = "0123456789";
        // when
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        FileBlock block = cappedOut.endBlock();
        cappedOut.sync();
        // then
        assertThat(block.startIndex()).isEqualTo(0);
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(10);
        assertThat(cappedDatabaseSizeKb).isEqualTo(1);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        byte[] bytes = new byte[10];
        in.readFully(bytes, 0, bytes.length);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrap() throws IOException {
        // given
        Writer out = new OutputStreamWriter(cappedOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        String text = sb.toString();
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        cappedOut.endBlock();
        // when
        out = new OutputStreamWriter(cappedOut);
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        FileBlock block = cappedOut.endBlock();
        // then
        assertThat(block.startIndex()).isEqualTo(600);
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(1200);
        assertThat(cappedDatabaseSizeKb).isEqualTo(1);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        byte[] bytes = new byte[600];
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + 600);
        in.readFully(bytes, 0, 424);
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES);
        in.readFully(bytes, 424, 176);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndKeepGoing() throws IOException {
        // given
        Writer out = new OutputStreamWriter(cappedOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        String text = sb.toString();
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        cappedOut.endBlock();
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        cappedOut.endBlock();
        // when
        out = new OutputStreamWriter(cappedOut);
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        FileBlock block = cappedOut.endBlock();
        // then
        assertThat(block.startIndex()).isEqualTo(1200);
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(1800);
        assertThat(cappedDatabaseSizeKb).isEqualTo(1);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        byte[] bytes = new byte[600];
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + 176);
        in.readFully(bytes, 0, bytes.length);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndResize() throws IOException {
        // given
        Writer out = new OutputStreamWriter(cappedOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        String text = sb.toString();
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        cappedOut.endBlock();
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        FileBlock block = cappedOut.endBlock();
        // when
        // have to close in before resizing
        in.close();
        cappedOut.resize(2);
        in = new RandomAccessFile(tempFile, "r");
        // then
        assertThat(block.startIndex()).isEqualTo(600);
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(1200);
        assertThat(cappedDatabaseSizeKb).isEqualTo(2);
        assertThat(lastCompactionBaseIndex).isEqualTo(176);
        byte[] bytes = new byte[600];
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + 424);
        in.readFully(bytes, 0, 600);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }
}
