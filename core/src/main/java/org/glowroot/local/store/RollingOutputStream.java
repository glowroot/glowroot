/*
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
package org.glowroot.local.store;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import checkers.nullness.quals.MonotonicNonNull;
import com.google.common.base.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.glowroot.common.Nullness.castNonNull;

/**
 * Needs to be externally synchronized around startBlock()/write()/endBlock().
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class RollingOutputStream extends OutputStream {

    static final int HEADER_SKIP_BYTES = 20;

    private static final Logger logger = LoggerFactory.getLogger(RollingOutputStream.class);

    private static final int FSYNC_INTERVAL_MILLIS = 2000;
    private static final int HEADER_CURR_INDEX_POS = 0;

    private final File file;
    private final Ticker ticker;
    private RandomAccessFile out;

    // currIndex is ever-increasing even over rolling boundary
    // (btw it would take writing 2.9g per second for 100 years for currIndex to hit Long.MAX_VALUE)
    private long currIndex;
    // lastResizeBaseIndex is the smallest currIndex saved during the last resize
    private long lastResizeBaseIndex;
    // currPosition is the current position in the file
    private long currPosition;
    // rollingSizeKb is volatile so it can be read outside of the external synchronization around
    // startBlock()/write()/endBlock()
    private volatile int rollingSizeKb;
    private long rollingSizeBytes;

    private long blockStartIndex;

    private final AtomicBoolean fsyncNeeded = new AtomicBoolean();
    private final AtomicLong lastFsyncTick = new AtomicLong();

    @MonotonicNonNull
    private volatile Future<?> fsyncFuture;

    static RollingOutputStream create(File file, int requestedRollingSizeKb,
            ScheduledExecutorService scheduledExecutor, Ticker ticker) throws IOException {
        final RollingOutputStream out = new RollingOutputStream(file, requestedRollingSizeKb,
                ticker);
        out.fsyncFuture = scheduledExecutor.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try {
                    out.fsyncIfNeeded();
                } catch (Throwable t) {
                    // log and terminate successfully
                    logger.error(t.getMessage(), t);
                }
            }
        }, FSYNC_INTERVAL_MILLIS, FSYNC_INTERVAL_MILLIS, MILLISECONDS);
        return out;
    }

    private RollingOutputStream(File file, int requestedRollingSizeKb, Ticker ticker)
            throws IOException {
        this.file = file;
        this.ticker = ticker;
        boolean newFile = !file.exists() || file.length() == 0;
        out = new RandomAccessFile(file, "rw");
        if (newFile) {
            currIndex = 0;
            rollingSizeKb = requestedRollingSizeKb;
            rollingSizeBytes = rollingSizeKb * 1024L;
            lastResizeBaseIndex = 0;
            out.writeLong(currIndex);
            out.writeInt(rollingSizeKb);
            out.writeLong(lastResizeBaseIndex);
        } else {
            currIndex = out.readLong();
            // have to ignore requested fixedLength for existing files, must explicitly call
            // resize() since this can be an expensive operation
            rollingSizeKb = out.readInt();
            rollingSizeBytes = rollingSizeKb * 1024L;
            lastResizeBaseIndex = out.readLong();
            currPosition = (currIndex - lastResizeBaseIndex) % rollingSizeBytes;
            out.seek(HEADER_SKIP_BYTES + currPosition);
        }
        lastFsyncTick.set(ticker.read());
    }

    void startBlock() {
        blockStartIndex = currIndex;
    }

    FileBlock endBlock() {
        fsyncNeeded.set(true);
        if (ticker.read() - lastFsyncTick.get() > SECONDS.toNanos(5)) {
            // scheduled fsyncs must have fallen behind (since they share a single thread with other
            // tasks in order to keep number of threads down), so force an fsync now
            fsyncIfNeeded();
        }
        return FileBlock.from(blockStartIndex, currIndex - blockStartIndex);
    }

    boolean isRolledOver(FileBlock block) {
        // need to check lastResizeBaseIndex in case it was recently resized larger, in which case
        // currIndex - rollingSizeBytes would be less than lastResizeBaseIndex
        return block.getStartIndex() < lastResizeBaseIndex
                || block.getStartIndex() < currIndex - rollingSizeBytes;
    }

    // this is ok to read outside of external synchronization around startBlock()/write()/endBlock()
    int getRollingSizeKb() {
        return rollingSizeKb;
    }

    long convertToFilePosition(long index) {
        return (index - lastResizeBaseIndex) % rollingSizeBytes;
    }

    // perform resize in-place to avoid using extra disk space
    void resize(int newRollingSizeKb) throws IOException {
        if (newRollingSizeKb == rollingSizeKb) {
            return;
        }
        long newRollingSizeBytes = newRollingSizeKb * 1024L;
        if (newRollingSizeKb < rollingSizeKb
                && currIndex - lastResizeBaseIndex < newRollingSizeBytes) {
            // resizing smaller and on first "loop" after a resize and haven't written up to the
            // new smaller size yet
            out.seek(8);
            out.writeInt(newRollingSizeKb);
            rollingSizeKb = newRollingSizeKb;
            rollingSizeBytes = newRollingSizeBytes;
            return;
        } else if (newRollingSizeKb > rollingSizeKb
                && currIndex - lastResizeBaseIndex < rollingSizeBytes) {
            // resizing larger and on first "loop" after a resize
            out.seek(8);
            out.writeInt(newRollingSizeKb);
            rollingSizeKb = newRollingSizeKb;
            rollingSizeBytes = newRollingSizeBytes;
            return;
        }
        // keep the min of the current and new rolling size
        int numKeepKb = Math.min(rollingSizeKb, newRollingSizeKb);
        long numKeepBytes = numKeepKb * 1024L;
        // at this point, because of the two shortcut conditionals above, currIndex must be >=
        // either the current or new rolling size (numKeepBytes)
        long startPosition = convertToFilePosition(currIndex - numKeepBytes);
        lastResizeBaseIndex = currIndex - numKeepBytes;
        File tmpRollingFile = new File(file.getPath() + ".resizing.tmp");
        RandomAccessFile tmpOut = new RandomAccessFile(tmpRollingFile, "rw");
        tmpOut.writeLong(currIndex);
        tmpOut.writeInt(newRollingSizeKb);
        tmpOut.writeLong(lastResizeBaseIndex);
        long remaining = rollingSizeBytes - startPosition;
        if (numKeepBytes > remaining) {
            out.seek(HEADER_SKIP_BYTES + startPosition);
            copy(out, tmpOut, remaining);
            out.seek(HEADER_SKIP_BYTES);
            copy(out, tmpOut, numKeepBytes - remaining);
        } else {
            out.seek(HEADER_SKIP_BYTES + startPosition);
            copy(out, tmpOut, numKeepBytes);
        }
        out.close();
        tmpOut.close();
        if (!file.delete()) {
            throw new IOException("Unable to delete existing rolling file during resize");
        }
        if (!tmpRollingFile.renameTo(file)) {
            throw new IOException("Unable to rename new rolling file during resize");
        }
        rollingSizeKb = newRollingSizeKb;
        rollingSizeBytes = newRollingSizeBytes;
        if (numKeepBytes == newRollingSizeBytes) {
            // shrunk and filled up file
            currPosition = 0;
        } else {
            currPosition = numKeepBytes;
        }
        out = new RandomAccessFile(file, "rw");
        out.seek(HEADER_SKIP_BYTES + currPosition);
    }

    @Override
    public void close() throws IOException {
        // factory method ensures fsyncFuture is not null
        castNonNull(fsyncFuture);
        fsyncFuture.cancel(false);
        out.close();
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (currIndex + len - blockStartIndex > rollingSizeBytes) {
            throw new IOException("A single block cannot have more bytes than size of the rolling"
                    + " file");
        }
        // update header before writing data in case of abnormal shutdown during writing data
        currIndex += len;
        out.seek(HEADER_CURR_INDEX_POS);
        out.writeLong(currIndex);
        out.seek(HEADER_SKIP_BYTES + currPosition);

        long remaining = rollingSizeBytes - currPosition;
        if (len >= remaining) {
            // intentionally handling == case here
            out.write(b, off, (int) remaining);
            out.seek(HEADER_SKIP_BYTES);
            out.write(b, (int) remaining, (int) (len - remaining));
            currPosition = len - remaining;
        } else {
            out.write(b, off, len);
            currPosition += len;
        }
    }

    private void fsyncIfNeeded() {
        if (fsyncNeeded.getAndSet(false)) {
            try {
                out.getFD().sync();
                lastFsyncTick.set(ticker.read());
            } catch (SyncFailedException e) {
                logger.error(e.getMessage(), e);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @OnlyUsedByTests
    void sync() throws IOException {
        out.getFD().sync();
    }

    private static void copy(RandomAccessFile in, RandomAccessFile out, long numBytes)
            throws IOException {
        byte[] block = new byte[1024];
        long total = 0;
        while (total < numBytes) {
            int n = in.read(block, 0, (int) Math.min(1024L, numBytes - total));
            out.write(block, 0, n);
            total += n;
        }
    }
}
