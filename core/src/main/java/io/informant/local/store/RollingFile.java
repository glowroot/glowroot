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

import io.informant.util.NotThreadSafe;
import io.informant.util.ThreadSafe;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.Writer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.lock.quals.GuardedBy;

import com.google.common.base.Charsets;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Longs;
import com.ning.compress.lzf.LZFInputStream;
import com.ning.compress.lzf.LZFOutputStream;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class RollingFile {

    private static final Logger logger = LoggerFactory.getLogger(RollingFile.class);

    private final File file;
    @GuardedBy("lock")
    private final RollingOutputStream out;
    @GuardedBy("lock")
    private final Writer compressedWriter;
    @GuardedBy("lock")
    private RandomAccessFile inFile;
    private final Object lock = new Object();
    private volatile boolean closing = false;

    RollingFile(File file, int requestedRollingSizeKb) throws IOException {
        this.file = file;
        out = new RollingOutputStream(file, requestedRollingSizeKb);
        compressedWriter = new OutputStreamWriter(new LZFOutputStream(out), Charsets.UTF_8);
        inFile = new RandomAccessFile(file, "r");
    }

    FileBlock write(CharSource charSource) {
        synchronized (lock) {
            if (closing) {
                return FileBlock.expired();
            }
            out.startBlock();
            try {
                charSource.copyTo(compressedWriter);
                compressedWriter.flush();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                return FileBlock.expired();
            }
            return out.endBlock();
        }
    }

    CharSource read(FileBlock block, String rolledOverResponse) {
        return new FileBlockCharSource(block, rolledOverResponse);
    }

    public void resize(int newRollingSizeKb) throws IOException {
        synchronized (lock) {
            if (closing) {
                return;
            }
            inFile.close();
            out.resize(newRollingSizeKb);
            inFile = new RandomAccessFile(file, "r");
        }
    }

    void close() throws IOException {
        logger.debug("close()");
        synchronized (lock) {
            closing = true;
            out.close();
            inFile.close();
        }
    }

    @ThreadSafe
    private class FileBlockCharSource extends CharSource {

        private final FileBlock block;
        private final String rolledOverResponse;

        private FileBlockCharSource(FileBlock block, String rolledOverResponse) {
            this.block = block;
            this.rolledOverResponse = rolledOverResponse;
        }

        @Override
        public Reader openStream() throws IOException {
            if (!out.stillExists(block)) {
                return CharStreams.asCharSource(rolledOverResponse).openStream();
            }
            // it's important to wrap FileBlockInputStream in a BufferedInputStream to prevent lots
            // of small reads from the underlying RandomAccessFile
            final int bufferSize = 32768;
            return new InputStreamReader(new LZFInputStream(new BufferedInputStream(
                    new FileBlockInputStream(block), bufferSize)));
        }
    }

    @NotThreadSafe
    private class FileBlockInputStream extends InputStream {

        private final FileBlock block;
        private long blockIndex;

        private FileBlockInputStream(FileBlock block) {
            this.block = block;
        }

        @Override
        public int read(byte bytes[], int off, int len) throws IOException {
            long blockRemaining = block.getLength() - blockIndex;
            if (blockRemaining == 0) {
                return -1;
            }
            synchronized (lock) {
                if (!out.stillExists(block)) {
                    throw new IOException("Block rolled out mid-read");
                }
                long filePosition = out.convertToFilePosition(block.getStartIndex() + blockIndex);
                inFile.seek(RollingOutputStream.HEADER_SKIP_BYTES + filePosition);
                long fileRemaining = out.getRollingSizeKb() * 1024 - filePosition;
                int numToRead = (int) Longs.min(len, blockRemaining, fileRemaining);
                RandomAccessFiles.readFully(inFile, bytes, off, numToRead);
                blockIndex += numToRead;
                return numToRead;
            }
        }
        // delegate to read(...) above
        @Override
        public int read(byte bytes[]) throws IOException {
            return read(bytes, 0, bytes.length);
        }

        // delegate to read(...) above, though this should never get called since
        // FileBlockInputStream is wrapped in BufferedInputStream
        @Override
        public int read() throws IOException {
            logger.warn("read() performs very poorly, FileBlockInputStream should always be"
                    + " wrapped in BufferedInputStream");
            byte[] bytes = new byte[1];
            if (read(bytes, 0, 1) == -1) {
                return -1;
            } else {
                return bytes[0];
            }
        }
    }
}
