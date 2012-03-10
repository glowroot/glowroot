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

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.ning.compress.lzf.LZFDecoder;
import com.ning.compress.lzf.LZFOutputStream;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RollingFile {

    private static final Logger logger = LoggerFactory.getLogger(RollingFile.class);

    private final File rollingFile;
    private final RollingOutputStream rollingOut;
    private final Writer compressedOut;

    // guarded by lock
    private RandomAccessFile inFile;

    private final Object lock = new Object();

    // TODO handle exceptions better
    public RollingFile(File rollingFile, int requestedRollingSizeKb) throws IOException {
        this.rollingFile = rollingFile;
        rollingOut = new RollingOutputStream(rollingFile, requestedRollingSizeKb);
        compressedOut = new OutputStreamWriter(new LZFOutputStream(rollingOut), Charsets.UTF_8);
        inFile = new RandomAccessFile(rollingFile, "r");
    }

    // TODO handle exceptions better
    public FileBlock write(CharSequence s) throws IOException {
        synchronized (lock) {
            rollingOut.startBlock();
            compressedOut.write(s.toString());
            compressedOut.flush();
            return rollingOut.endBlock();
        }
    }

    public String read(FileBlock block) throws IOException, FileBlockNoLongerExists {
        if (block.getLength() > Integer.MAX_VALUE) {
            logger.error("cannot read more than Integer.MAX_VALUE bytes", new Throwable());
        }
        synchronized (lock) {
            if (!rollingOut.stillExists(block)) {
                throw new FileBlockNoLongerExists();
            }
            long filePosition = rollingOut.convertToFilePosition(block.getStartIndex());
            inFile.seek(RollingOutputStream.HEADER_SKIP_BYTES + filePosition);
            byte[] bytes = new byte[(int) block.getLength()];
            long remaining = rollingOut.getRollingSizeBytes() - filePosition;
            if (block.getLength() > remaining) {
                RandomAccessFileUtil.readFully(inFile, bytes, 0, (int) remaining);
                inFile.seek(RollingOutputStream.HEADER_SKIP_BYTES);
                RandomAccessFileUtil.readFully(inFile, bytes, (int) remaining,
                        (int) (block.getLength() - remaining));
            } else {
                RandomAccessFileUtil.readFully(inFile, bytes);
            }
            return new String(LZFDecoder.decode(bytes), Charsets.UTF_8.name());
        }
    }

    public void resize(int newRollingSizeKb) throws IOException {
        synchronized (lock) {
            inFile.close();
            rollingOut.resize(newRollingSizeKb);
            inFile = new RandomAccessFile(rollingFile, "r");
        }
    }

    public double getRollingSizeKb() {
        synchronized (lock) {
            return rollingOut.getRollingSizeBytes() / (double) 1024;
        }
    }

    public void shutdown() {
        logger.debug("shutdown()");
        synchronized (lock) {
            rollingOut.shutdown();
        }
    }

    @SuppressWarnings("serial")
    public static class FileBlockNoLongerExists extends Exception {}
}
