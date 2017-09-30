/*
 * Copyright 2012-2017 the original author or authors.
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.util.List;
import java.util.Map;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Charsets;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.ByteSource;
import com.google.common.io.CharSource;
import com.google.common.io.CountingOutputStream;
import com.google.common.primitives.Longs;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.ning.compress.lzf.LZFInputStream;
import com.ning.compress.lzf.LZFOutputStream;
import com.ning.compress.lzf.util.ChunkDecoderFactory;
import com.ning.compress.lzf.util.ChunkEncoderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.util.AppServerDetection;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.SizeLimitBypassingParser;

public class CappedDatabase {

    private static final Logger logger = LoggerFactory.getLogger(CappedDatabase.class);

    private final File file;
    private final Object lock = new Object();
    @GuardedBy("lock")
    private final CappedDatabaseOutputStream out;
    private final Thread shutdownHookThread;
    @GuardedBy("lock")
    private RandomAccessFile inFile;
    private volatile boolean closed = false;

    private final Ticker ticker;
    private final Map<String, CappedDatabaseStats> statsByType = Maps.newHashMap();

    public CappedDatabase(File file, int requestedSizeKb, Ticker ticker) throws IOException {
        this.file = file;
        this.ticker = ticker;
        out = new CappedDatabaseOutputStream(file, requestedSizeKb);
        inFile = new RandomAccessFile(file, "r");
        shutdownHookThread = new ShutdownHookThread();
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
    }

    public long writeMessage(final AbstractMessage message, String type) throws IOException {
        return write(type, new Copier() {
            @Override
            public void copyTo(OutputStream writer) throws IOException {
                message.writeTo(writer);
            }
        });
    }

    public long writeMessages(final List<? extends AbstractMessage> messages, String type)
            throws IOException {
        return write(type, new Copier() {
            @Override
            public void copyTo(OutputStream writer) throws IOException {
                for (AbstractMessage message : messages) {
                    message.writeDelimitedTo(writer);
                }
            }
        });
    }

    public CappedDatabaseStats getStats(String type) {
        CappedDatabaseStats stats = statsByType.get(type);
        if (stats == null) {
            return new CappedDatabaseStats();
        }
        return stats;
    }

    @OnlyUsedByTests
    long write(final ByteSource byteSource, String type) throws IOException {
        return write(type, new Copier() {
            @Override
            public void copyTo(OutputStream out) throws IOException {
                byteSource.copyTo(out);
            }
        });
    }

    private long write(String type, Copier copier) throws IOException {
        synchronized (lock) {
            if (closed) {
                return -1;
            }
            long startTick = ticker.read();
            out.startBlock();
            NonClosingCountingOutputStream countingStreamAfterCompression =
                    new NonClosingCountingOutputStream(out);
            CountingOutputStream countingStreamBeforeCompression =
                    new CountingOutputStream(newLZFOutputStream(countingStreamAfterCompression));
            copier.copyTo(countingStreamBeforeCompression);
            countingStreamBeforeCompression.close();
            long endTick = ticker.read();
            CappedDatabaseStats stats = statsByType.get(type);
            if (stats == null) {
                stats = new CappedDatabaseStats();
                statsByType.put(type, stats);
            }
            stats.record(countingStreamBeforeCompression.getCount(),
                    countingStreamAfterCompression.getCount(), endTick - startTick);
            return out.endBlock();
        }
    }

    public <T extends /*@NonNull*/ AbstractMessage> /*@Nullable*/ T readMessage(long cappedId,
            Parser<T> parser) throws IOException {
        boolean overwritten;
        boolean inTheFuture;
        synchronized (lock) {
            overwritten = out.isOverwritten(cappedId);
            inTheFuture = cappedId >= out.getCurrIndex();
        }
        if (overwritten) {
            return null;
        }
        if (inTheFuture) {
            // this can happen when the glowroot folder is copied for analysis without shutting down
            // the JVM and glowroot.capped.db is copied first, then new data is written to
            // glowroot.capped.db and the new capped ids are written to glowroot.h2.db and then
            // glowroot.h2.db is copied with capped ids that do not exist in the copied
            // glowroot.capped.db
            return null;
        }
        // it's important to wrap CappedBlockInputStream in a BufferedInputStream to prevent
        // lots of small reads from the underlying RandomAccessFile
        final int bufferSize = 32768;
        InputStream input = newLZFInputStream(
                new BufferedInputStream(new CappedBlockInputStream(cappedId), bufferSize));
        try {
            return parser.parseFrom(input);
        } catch (Exception e) {
            if (!out.isOverwritten(cappedId)) {
                logger.error(e.getMessage(), e);
            }
            return null;
        } finally {
            input.close();
        }
    }

    public <T extends /*@NonNull*/ MessageLite> List<T> readMessages(long cappedId,
            Parser<T> parser) throws IOException {
        boolean overwritten;
        boolean inTheFuture;
        synchronized (lock) {
            overwritten = out.isOverwritten(cappedId);
            inTheFuture = cappedId >= out.getCurrIndex();
        }
        if (overwritten) {
            return ImmutableList.of();
        }
        if (inTheFuture) {
            // this can happen when the glowroot folder is copied for analysis without shutting down
            // the JVM and glowroot.capped.db is copied first, then new data is written to
            // glowroot.capped.db and the new capped ids are written to glowroot.h2.db and then
            // glowroot.h2.db is copied with capped ids that do not exist in the copied
            // glowroot.capped.db
            return ImmutableList.of();
        }
        // it's important to wrap CappedBlockInputStream in a BufferedInputStream to prevent
        // lots of small reads from the underlying RandomAccessFile
        final int bufferSize = 32768;
        InputStream input = newLZFInputStream(
                new BufferedInputStream(new CappedBlockInputStream(cappedId), bufferSize));
        SizeLimitBypassingParser<T> sizeLimitBypassingParser =
                new SizeLimitBypassingParser<T>(parser);
        List<T> messages = Lists.newArrayList();
        try {
            T message;
            while ((message = sizeLimitBypassingParser.parseDelimitedFrom(input)) != null) {
                messages.add(message);
            }
        } catch (Exception e) {
            if (!out.isOverwritten(cappedId)) {
                logger.error(e.getMessage(), e);
            }
            return ImmutableList.of();
        } finally {
            input.close();
        }
        return messages;
    }

    @OnlyUsedByTests
    CharSource read(long cappedId) {
        return new CappedBlockCharSource(cappedId);
    }

    boolean isExpired(long cappedId) {
        synchronized (lock) {
            return out.isOverwritten(cappedId);
        }
    }

    public long getSmallestNonExpiredId() {
        synchronized (lock) {
            return out.getSmallestNonOverwrittenId();
        }
    }

    public void resize(int newSizeKb) throws IOException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            inFile.close();
            out.resize(newSizeKb);
            inFile = new RandomAccessFile(file, "r");
        }
    }

    @OnlyUsedByTests
    public void close() throws IOException {
        synchronized (lock) {
            closed = true;
            out.close();
            inFile.close();
        }
        Runtime.getRuntime().removeShutdownHook(shutdownHookThread);
    }

    private static LZFOutputStream newLZFOutputStream(OutputStream outputStream) {
        if (AppServerDetection.isIbmJvm() && JavaVersion.isJava6()) {
            return new LZFOutputStream(ChunkEncoderFactory.safeInstance(), outputStream);
        } else {
            return new LZFOutputStream(outputStream);
        }
    }

    private static LZFInputStream newLZFInputStream(InputStream inputStream) throws IOException {
        if (AppServerDetection.isIbmJvm() && JavaVersion.isJava6()) {
            return new LZFInputStream(ChunkDecoderFactory.safeInstance(), inputStream);
        } else {
            return new LZFInputStream(inputStream);
        }
    }

    @OnlyUsedByTests
    private class CappedBlockCharSource extends CharSource {

        private final long cappedId;

        private CappedBlockCharSource(long cappedId) {
            this.cappedId = cappedId;
        }

        @Override
        public Reader openStream() throws IOException {
            // it's important to wrap CappedBlockInputStream in a BufferedInputStream to prevent
            // lots of small reads from the underlying RandomAccessFile
            final int bufferSize = 32768;
            return new InputStreamReader(newLZFInputStream(
                    new BufferedInputStream(new CappedBlockInputStream(cappedId), bufferSize)),
                    Charsets.UTF_8);
        }
    }

    private class CappedBlockInputStream extends InputStream {

        private final long cappedId;
        private long blockLength = -1;
        private long blockIndex;

        private CappedBlockInputStream(long cappedId) {
            this.cappedId = cappedId;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            if (blockIndex == blockLength) {
                return -1;
            }
            synchronized (lock) {
                if (out.isOverwritten(cappedId)) {
                    throw new CappedBlockRolledOverMidReadException("Block rolled over mid-read");
                }
                if (blockLength == -1) {
                    long filePosition = out.convertToFilePosition(cappedId);
                    inFile.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + filePosition);
                    blockLength = inFile.readLong();
                }
                long filePosition = out.convertToFilePosition(
                        cappedId + CappedDatabaseOutputStream.BLOCK_HEADER_SKIP_BYTES + blockIndex);
                inFile.seek(CappedDatabaseOutputStream.HEADER_SKIP_BYTES + filePosition);
                long blockRemaining = blockLength - blockIndex;
                long fileRemaining = out.getSizeKb() * 1024L - filePosition;
                int numToRead = (int) Longs.min(len, blockRemaining, fileRemaining);
                inFile.readFully(bytes, off, numToRead);
                blockIndex += numToRead;
                return numToRead;
            }
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            // this is never called since CappedBlockInputStream is always wrapped in a
            // BufferedInputStream
            return read(bytes, 0, bytes.length);
        }

        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException(
                    "CappedBlockInputStream should always be wrapped in a BufferedInputStream");
        }
    }

    private class ShutdownHookThread extends Thread {
        @Override
        public void run() {
            try {
                // update flag outside of lock in case there is a backlog of threads already
                // waiting on the lock (once the flag is set, any threads in the backlog that
                // haven't acquired the lock will abort quickly once they do obtain the lock)
                closed = true;
                synchronized (lock) {
                    out.close();
                    inFile.close();
                }
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
    }

    private interface Copier {
        void copyTo(OutputStream out) throws IOException;
    }

    @SuppressWarnings("serial")
    private static class CappedBlockRolledOverMidReadException extends IOException {
        public CappedBlockRolledOverMidReadException(String message) {
            super(message);
        }
    }

    private static class NonClosingCountingOutputStream extends FilterOutputStream {

        private long count;

        private NonClosingCountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void close() {}

        private long getCount() {
            return count;
        }
    }
}
