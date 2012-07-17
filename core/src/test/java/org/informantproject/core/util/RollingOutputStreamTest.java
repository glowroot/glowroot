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
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;

import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RollingOutputStreamTest {

    @Test
    public void shouldWrite() throws IOException {
        // given
        File file = File.createTempFile("informant-unit-test-", "");
        RollingOutputStream rollingOut = new RollingOutputStream(file, 1);
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
        RandomAccessFile in = new RandomAccessFile(file, "r");
        long currIndex = in.readLong();
        int rollingSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(10);
        assertThat(rollingSizeKb).isEqualTo(1);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        byte[] bytes = new byte[10];
        RandomAccessFileUtil.readFully(in, bytes);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrap() throws IOException {
        // given
        File file = File.createTempFile("informant-unit-test-", "");
        RollingOutputStream rollingOut = new RollingOutputStream(file, 1);
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
        RandomAccessFile in = new RandomAccessFile(file, "r");
        long currIndex = in.readLong();
        int rollingSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(1200);
        assertThat(rollingSizeKb).isEqualTo(1);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        byte[] bytes = new byte[600];
        in.seek(RollingOutputStream.HEADER_SKIP_BYTES + 600);
        RandomAccessFileUtil.readFully(in, bytes, 0, 424);
        in.seek(RollingOutputStream.HEADER_SKIP_BYTES);
        RandomAccessFileUtil.readFully(in, bytes, 424, 176);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndKeepGoing() throws IOException {
        // given
        File file = File.createTempFile("informant-unit-test-", "");
        RollingOutputStream rollingOut = new RollingOutputStream(file, 1);
        Writer out = new OutputStreamWriter(rollingOut);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        String text = sb.toString();
        rollingOut.startBlock();
        out.write(text);
        out.flush();
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
        RandomAccessFile in = new RandomAccessFile(file, "r");
        long currIndex = in.readLong();
        int rollingSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(1800);
        assertThat(rollingSizeKb).isEqualTo(1);
        assertThat(lastCompactionBaseIndex).isEqualTo(0);
        byte[] bytes = new byte[600];
        in.seek(RollingOutputStream.HEADER_SKIP_BYTES + 176);
        RandomAccessFileUtil.readFully(in, bytes);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }

    @Test
    public void shouldWrapAndResize() throws IOException {
        // given
        File file = File.createTempFile("informant-unit-test-", "");
        RollingOutputStream rollingOut = new RollingOutputStream(file, 1);
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
        rollingOut.resize(2);
        // then
        assertThat(block.getStartIndex()).isEqualTo(600);
        RandomAccessFile in = new RandomAccessFile(file, "r");
        long currIndex = in.readLong();
        int rollingSizeKb = in.readInt();
        long lastCompactionBaseIndex = in.readLong();
        assertThat(currIndex).isEqualTo(1200);
        assertThat(rollingSizeKb).isEqualTo(2);
        assertThat(lastCompactionBaseIndex).isEqualTo(176);
        byte[] bytes = new byte[600];
        in.seek(RollingOutputStream.HEADER_SKIP_BYTES + 424);
        RandomAccessFileUtil.readFully(in, bytes, 0, 600);
        String content = new String(bytes);
        assertThat(content).isEqualTo(text);
    }
}
