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
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CappedDatabaseOutputStreamTest {

    private static final int BLOCK_HEADER_SIZE = 8;

    private File tempFile;
    private CappedDatabaseOutputStream cappedOut;
    private RandomAccessFile in;

    @Before
    public void onBefore() throws IOException {
        tempFile = File.createTempFile("glowroot-test-", ".capped.txt");
        cappedOut = new CappedDatabaseOutputStream(tempFile, 10);
        in = new RandomAccessFile(tempFile, "r");
    }

    @After
    public void onAfter() throws IOException {
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
        long cappedId = cappedOut.endBlock();
        cappedOut.sync();

        // then
        assertWrite(text, cappedId);
    }

    @Test
    public void shouldWriteUsingByteArray() throws IOException {
        // given
        String text = "0123456789";

        // when
        cappedOut.startBlock();
        cappedOut.write(text.getBytes());
        cappedOut.flush();
        long cappedId = cappedOut.endBlock();
        cappedOut.sync();

        // then
        assertWrite(text, cappedId);
    }

    @Test
    public void shouldWriteUsingSingleBytes() throws IOException {
        // when
        cappedOut.startBlock();
        cappedOut.write('0');
        cappedOut.write('1');
        cappedOut.write('2');
        cappedOut.write('3');
        cappedOut.write('4');
        cappedOut.write('5');
        cappedOut.write('6');
        cappedOut.write('7');
        cappedOut.write('8');
        cappedOut.write('9');
        cappedOut.flush();
        long cappedId = cappedOut.endBlock();
        cappedOut.sync();

        // then
        assertWrite("0123456789", cappedId);
    }

    @Test
    public void shouldWrap() throws IOException {
        // given
        Writer out = new OutputStreamWriter(cappedOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
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
        long cappedId = cappedOut.endBlock();

        // then
        assertThat(cappedId).isEqualTo(6000 + BLOCK_HEADER_SIZE);
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(12000 + 2 * BLOCK_HEADER_SIZE);
        assertThat(cappedDatabaseSizeKb).isEqualTo(10);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + 6000 + BLOCK_HEADER_SIZE);
        long blockSize = in.readLong();
        assertThat(blockSize).isEqualTo(6000);
        byte[] bytes = new byte[(int) blockSize];
        int remaining = 10240 - 6000 - 2 * BLOCK_HEADER_SIZE;
        in.readFully(bytes, 0, remaining);
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES);
        in.readFully(bytes, remaining, 6000 - remaining);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndKeepGoing() throws IOException {
        // given
        Writer out = new OutputStreamWriter(cappedOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
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
        long cappedId = cappedOut.endBlock();

        // then
        assertThat(cappedId).isEqualTo(12000 + 2 * BLOCK_HEADER_SIZE);
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(18000 + 3 * BLOCK_HEADER_SIZE);
        assertThat(cappedDatabaseSizeKb).isEqualTo(10);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        int totalOfFirstTwoBlocks = 2 * (6000 + BLOCK_HEADER_SIZE);
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + totalOfFirstTwoBlocks - 10240);
        long blockSize = in.readLong();
        assertThat(blockSize).isEqualTo(6000);
        byte[] bytes = new byte[(int) blockSize];
        in.readFully(bytes, 0, bytes.length);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndResize() throws IOException {
        // given
        Writer out = new OutputStreamWriter(cappedOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
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
        long cappedId = cappedOut.endBlock();

        // when
        // have to close in before resizing
        in.close();
        cappedOut.resize(20);
        in = new RandomAccessFile(tempFile, "r");

        // then
        assertThat(cappedId).isEqualTo(6000 + BLOCK_HEADER_SIZE);
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(12000 + 2 * BLOCK_HEADER_SIZE);
        assertThat(cappedDatabaseSizeKb).isEqualTo(20);
        int total = 2 * (6000 + BLOCK_HEADER_SIZE);
        assertThat(lastCompactionBaseIndex).isEqualTo(total - 10240);
        int totalOfFirstBlock = 6000 + BLOCK_HEADER_SIZE;
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + 10240 - totalOfFirstBlock);
        long blockSize = in.readLong();
        assertThat(blockSize).isEqualTo(6000);
        byte[] bytes = new byte[(int) blockSize];
        in.readFully(bytes, 0, 6000);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndResizeVerySmall() throws IOException {
        // given
        Writer out = new OutputStreamWriter(cappedOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        String text = sb.toString();
        for (int i = 0; i < 9; i++) {
            cappedOut.startBlock();
            out.write(text);
            out.flush();
            cappedOut.endBlock();
        }
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        long cappedId = cappedOut.endBlock();

        // when
        // have to close in before resizing
        in.close();
        cappedOut.resize(1);
        in = new RandomAccessFile(tempFile, "r");

        // then
        assertThat(cappedId).isEqualTo(9 * (600 + BLOCK_HEADER_SIZE));
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(10 * (600 + BLOCK_HEADER_SIZE));
        assertThat(cappedDatabaseSizeKb).isEqualTo(1);
        int total = 10 * (600 + BLOCK_HEADER_SIZE);
        assertThat(lastCompactionBaseIndex).isEqualTo(total - 1024);
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + 416);
        long blockSize = in.readLong();
        assertThat(blockSize).isEqualTo(600);
        byte[] bytes = new byte[(int) blockSize];
        in.readFully(bytes, 0, 600);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapWithoutEnoughSpaceAtEndForContiguousBlockHeader() throws IOException {
        // given
        String text = "0123456789";
        cappedOut.startBlock();
        int numBytesToWrite = 10240 - BLOCK_HEADER_SIZE - 1;
        for (int i = 0; i < numBytesToWrite; i++) {
            cappedOut.write(0);
        }
        cappedOut.flush();
        cappedOut.endBlock();

        // when
        Writer out = new OutputStreamWriter(cappedOut);
        out = new OutputStreamWriter(cappedOut);
        cappedOut.startBlock();
        out.write(text);
        out.flush();
        long cappedId = cappedOut.endBlock();

        // then
        assertThat(cappedId).isEqualTo(10240);
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(10240 + BLOCK_HEADER_SIZE + text.length());
        assertThat(cappedDatabaseSizeKb).isEqualTo(10);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        in.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES);
        long blockSize = in.readLong();
        assertThat(blockSize).isEqualTo(text.length());
        byte[] bytes = new byte[(int) blockSize];
        in.readFully(bytes, 0, text.length());
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    private void assertWrite(String text, long cappedId) throws IOException {
        assertThat(cappedId).isEqualTo(0);
        long currIndex = in.readLong();
        int cappedDatabaseSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(10 + BLOCK_HEADER_SIZE);
        assertThat(cappedDatabaseSizeKb).isEqualTo(10);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        long blockSize = in.readLong();
        assertThat(blockSize).isEqualTo(10);
        byte[] bytes = new byte[(int) blockSize];
        in.readFully(bytes, 0, bytes.length);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }
}
