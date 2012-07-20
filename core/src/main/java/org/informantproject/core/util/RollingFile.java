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
import java.io.OutputStream;
import java.io.RandomAccessFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final OutputStream compressedOut;

    // guarded by lock
    private RandomAccessFile inFile;

    private final Object lock = new Object();

    // TODO handle exceptions better
    public RollingFile(File rollingFile, int requestedRollingSizeKb) throws IOException {
        this.rollingFile = rollingFile;
        rollingOut = new RollingOutputStream(rollingFile, requestedRollingSizeKb);
        compressedOut = new LZFOutputStream(rollingOut);
        inFile = new RandomAccessFile(rollingFile, "r");
    }

    // TODO handle exceptions better
    public FileBlock write(ByteStream byteStream) throws IOException {
        synchronized (lock) {
            rollingOut.startBlock();
            byteStream.writeTo(compressedOut);
            compressedOut.flush();
            return rollingOut.endBlock();
        }
    }

    public ByteStream read(FileBlock block) {
        return new FileBlockByteStream(block);
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

    public void close() throws IOException {
        logger.debug("close()");
        synchronized (lock) {
            rollingOut.close();
            inFile.close();
        }
    }

    // only used by tests
    public void closeAndDeleteFile() throws IOException {
        logger.debug("closeAndDeleteFile()");
        close();
        Files.delete(rollingFile);
    }

    private class FileBlockByteStream extends ByteStream {

        private final FileBlock block;
        private boolean end;

        private FileBlockByteStream(FileBlock block) {
            this.block = block;
        }

        @Override
        public boolean hasNext() {
            return !end;
        }

        @Override
        public byte[] next() throws IOException {
            if (block.getLength() > Integer.MAX_VALUE) {
                logger.error("cannot read more than Integer.MAX_VALUE bytes", new Throwable());
            }
            synchronized (lock) {
                if (!rollingOut.stillExists(block)) {
                    // TODO handle not exists case better?
                    return new byte[0];
                }
                long filePosition = rollingOut.convertToFilePosition(block.getStartIndex());
                inFile.seek(RollingOutputStream.HEADER_SKIP_BYTES + filePosition);
                byte[] bytes = new byte[(int) block.getLength()];
                long remaining = rollingOut.getRollingSizeBytes() - filePosition;
                if (block.getLength() > remaining) {
                    RandomAccessFiles.readFully(inFile, bytes, 0, (int) remaining);
                    inFile.seek(RollingOutputStream.HEADER_SKIP_BYTES);
                    RandomAccessFiles.readFully(inFile, bytes, (int) remaining,
                            (int) (block.getLength() - remaining));
                } else {
                    RandomAccessFiles.readFully(inFile, bytes);
                }
                end = true;
                return LZFDecoder.decode(bytes);
            }
        }
    }
}
