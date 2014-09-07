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
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ScheduledRunnable;
import org.glowroot.common.Ticker;
import org.glowroot.markers.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Needs to be externally synchronized around startBlock()/write()/endBlock().
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class CappedDatabaseOutputStream extends OutputStream {

    static final int HEADER_SKIP_BYTES = 20;

    private static final Logger logger = LoggerFactory.getLogger(CappedDatabaseOutputStream.class);

    private static final int FSYNC_INTERVAL_MILLIS = 2000;
    private static final int HEADER_CURR_INDEX_POS = 0;

    private final File file;
    private final Ticker ticker;
    private RandomAccessFile out;

    // currIndex is ever-increasing even over capped boundary
    // (btw it would take writing 2.9g per second for 100 years for currIndex to hit Long.MAX_VALUE)
    private long currIndex;
    // lastResizeBaseIndex is the smallest currIndex saved during the last resize
    private long lastResizeBaseIndex;
    // sizeKb is volatile so it can be read outside of the external synchronization around
    // startBlock()/write()/endBlock()
    private volatile int sizeKb;
    private long sizeBytes;

    private long blockStartIndex;

    private final AtomicBoolean fsyncNeeded = new AtomicBoolean();
    private final AtomicLong lastFsyncTick = new AtomicLong();

    private final FsyncRunnable fsyncScheduledRunnable;

    static CappedDatabaseOutputStream create(File file, int requestedSizeKb,
            ScheduledExecutorService scheduledExecutor, Ticker ticker) throws IOException {
        CappedDatabaseOutputStream out =
                new CappedDatabaseOutputStream(file, requestedSizeKb, ticker);
        out.fsyncScheduledRunnable.scheduleWithFixedDelay(scheduledExecutor, FSYNC_INTERVAL_MILLIS,
                FSYNC_INTERVAL_MILLIS, MILLISECONDS);
        return out;
    }

    private CappedDatabaseOutputStream(File file, int requestedSizeKb, Ticker ticker)
            throws IOException {
        this.file = file;
        this.ticker = ticker;
        boolean newFile = !file.exists() || file.length() == 0;
        out = new RandomAccessFile(file, "rw");
        if (newFile) {
            currIndex = 0;
            sizeKb = requestedSizeKb;
            sizeBytes = sizeKb * 1024L;
            lastResizeBaseIndex = 0;
            out.writeLong(currIndex);
            out.writeInt(sizeKb);
            out.writeLong(lastResizeBaseIndex);
        } else {
            currIndex = out.readLong();
            // have to ignore requested fixedLength for existing files, must explicitly call
            // resize() since this can be an expensive operation
            sizeKb = out.readInt();
            sizeBytes = sizeKb * 1024L;
            lastResizeBaseIndex = out.readLong();
        }
        lastFsyncTick.set(ticker.read());
        fsyncScheduledRunnable = new FsyncRunnable();
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

    boolean isOverwritten(FileBlock block) {
        // need to check lastResizeBaseIndex in case it was recently resized larger, in which case
        // currIndex - sizeBytes would be less than lastResizeBaseIndex
        return block.getStartIndex() < lastResizeBaseIndex
                || block.getStartIndex() < currIndex - sizeBytes;
    }

    // this is ok to read outside of external synchronization around startBlock()/write()/endBlock()
    int getSizeKb() {
        return sizeKb;
    }

    long convertToFilePosition(long index) {
        return (index - lastResizeBaseIndex) % sizeBytes;
    }

    // perform resize in-place to avoid using extra disk space
    void resize(int newSizeKb) throws IOException {
        if (newSizeKb == sizeKb) {
            return;
        }
        long newSizeBytes = newSizeKb * 1024L;
        if (newSizeKb < sizeKb
                && currIndex - lastResizeBaseIndex < newSizeBytes) {
            // resizing smaller and on first "loop" after a resize and haven't written up to the
            // new smaller size yet
            out.seek(8);
            out.writeInt(newSizeKb);
            sizeKb = newSizeKb;
            sizeBytes = newSizeBytes;
            return;
        } else if (newSizeKb > sizeKb
                && currIndex - lastResizeBaseIndex < sizeBytes) {
            // resizing larger and on first "loop" after a resize
            out.seek(8);
            out.writeInt(newSizeKb);
            sizeKb = newSizeKb;
            sizeBytes = newSizeBytes;
            return;
        }
        // keep the min of the current and new capped size
        int numKeepKb = Math.min(sizeKb, newSizeKb);
        long numKeepBytes = numKeepKb * 1024L;
        // at this point, because of the two shortcut conditionals above, currIndex must be >=
        // either the current or new capped size (numKeepBytes)
        long startPosition = convertToFilePosition(currIndex - numKeepBytes);
        lastResizeBaseIndex = currIndex - numKeepBytes;
        File tmpCappedFile = new File(file.getPath() + ".resizing.tmp");
        RandomAccessFile tmpOut = new RandomAccessFile(tmpCappedFile, "rw");
        tmpOut.writeLong(currIndex);
        tmpOut.writeInt(newSizeKb);
        tmpOut.writeLong(lastResizeBaseIndex);
        long remaining = sizeBytes - startPosition;
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
            throw new IOException("Unable to delete existing capped database during resize");
        }
        if (!tmpCappedFile.renameTo(file)) {
            throw new IOException("Unable to rename new capped database during resize");
        }
        sizeKb = newSizeKb;
        sizeBytes = newSizeBytes;
        out = new RandomAccessFile(file, "rw");
    }

    @Override
    public void close() throws IOException {
        fsyncScheduledRunnable.cancel();
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
        if (currIndex + len - blockStartIndex > sizeBytes) {
            throw new IOException("A single block cannot have more bytes than size of the capped"
                    + " database");
        }
        long currPosition = (currIndex - lastResizeBaseIndex) % sizeBytes;
        out.seek(HEADER_SKIP_BYTES + currPosition);
        long remaining = sizeBytes - currPosition;
        if (len >= remaining) {
            // intentionally handling == case here
            out.write(b, off, (int) remaining);
            out.seek(HEADER_SKIP_BYTES);
            out.write(b, (int) remaining, (int) (len - remaining));
        } else {
            out.write(b, off, len);
        }
        currIndex += len;
        out.seek(HEADER_CURR_INDEX_POS);
        out.writeLong(currIndex);
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

    private class FsyncRunnable extends ScheduledRunnable {
        @Override
        protected void runInternal() {
            fsyncIfNeeded();
        }
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
