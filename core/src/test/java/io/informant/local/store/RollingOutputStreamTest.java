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
import io.informant.util.DaemonExecutors;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RollingOutputStreamTest {

    private File tempFile;
    private ScheduledExecutorService scheduledExecutor;
    private RollingOutputStream rollingOut;
    private RandomAccessFile in;

    @Before
    public void onBefore() throws IOException {
        tempFile = File.createTempFile("informant-test-", ".rolling.txt");
        scheduledExecutor = DaemonExecutors.newSingleThreadScheduledExecutor("Informant-Fsync");
        rollingOut = new RollingOutputStream(tempFile, 1, scheduledExecutor, Ticker.systemTicker());
        in = new RandomAccessFile(tempFile, "r");
    }

    @After
    public void onAfter() throws IOException {
        scheduledExecutor.shutdownNow();
        rollingOut.close();
        in.close();
        tempFile.delete();
    }

    @Test
    public void shouldWrite() throws IOException {
        // given
        Writer out = new OutputStreamWriter(rollingOut);
        String text = "0123456789";
        // when
        rollingOut.startBlock();
        out.write(text);
        out.flush();
        FileBlock block = rollingOut.endBlock();
        rollingOut.sync();
        // then
        assertThat(block.getStartIndex()).isEqualTo(0);
        long currIndex = in.readLong();
        int rollingSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(10);
        assertThat(rollingSizeKb).isEqualTo(1);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        byte[] bytes = new byte[10];
        RandomAccessFiles.readFully(in, bytes, 0, bytes.length);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrap() throws IOException {
        // given
        Writer out = new OutputStreamWriter(rollingOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        String text = sb.toString();
        rollingOut.startBlock();
        out.write(text);
        out.flush();
        rollingOut.endBlock();
        // when
        out = new OutputStreamWriter(rollingOut);
        rollingOut.startBlock();
        out.write(text);
        out.flush();
        FileBlock block = rollingOut.endBlock();
        // then
        assertThat(block.getStartIndex()).isEqualTo(600);
        long currIndex = in.readLong();
        int rollingSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(1200);
        assertThat(rollingSizeKb).isEqualTo(1);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        byte[] bytes = new byte[600];
        in.seek(RollingOutputStream.HEADER_SKIP_BYTES + 600);
        RandomAccessFiles.readFully(in, bytes, 0, 424);
        in.seek(RollingOutputStream.HEADER_SKIP_BYTES);
        RandomAccessFiles.readFully(in, bytes, 424, 176);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndKeepGoing() throws IOException {
        // given
        Writer out = new OutputStreamWriter(rollingOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        String text = sb.toString();
        rollingOut.startBlock();
        out.write(text);
        out.flush();
        rollingOut.endBlock();
        rollingOut.startBlock();
        out.write(text);
        out.flush();
        rollingOut.endBlock();
        // when
        out = new OutputStreamWriter(rollingOut);
        rollingOut.startBlock();
        out.write(text);
        out.flush();
        FileBlock block = rollingOut.endBlock();
        // then
        assertThat(block.getStartIndex()).isEqualTo(1200);
        long currIndex = in.readLong();
        int rollingSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(1800);
        assertThat(rollingSizeKb).isEqualTo(1);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        byte[] bytes = new byte[600];
        in.seek(RollingOutputStream.HEADER_SKIP_BYTES + 176);
        RandomAccessFiles.readFully(in, bytes, 0, bytes.length);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndResize() throws IOException {
        // given
        Writer out = new OutputStreamWriter(rollingOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        String text = sb.toString();
        rollingOut.startBlock();
        out.write(text);
        out.flush();
        rollingOut.endBlock();
        rollingOut.startBlock();
        out.write(text);
        out.flush();
        FileBlock block = rollingOut.endBlock();
        // when
        // have to close in before resizing
        in.close();
        rollingOut.resize(2);
        in = new RandomAccessFile(tempFile, "r");
        // then
        assertThat(block.getStartIndex()).isEqualTo(600);
        long currIndex = in.readLong();
        int rollingSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(1200);
        assertThat(rollingSizeKb).isEqualTo(2);
        assertThat(lastCompactionBaseIndex).isEqualTo(176);
        byte[] bytes = new byte[600];
        in.seek(RollingOutputStream.HEADER_SKIP_BYTES + 424);
        RandomAccessFiles.readFully(in, bytes, 0, 600);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }
}
