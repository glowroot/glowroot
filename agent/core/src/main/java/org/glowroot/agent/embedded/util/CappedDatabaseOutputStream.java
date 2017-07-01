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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

import org.glowroot.common.util.OnlyUsedByTests;

// Needs to be externally synchronized around startBlock()/write()/endBlock().
class CappedDatabaseOutputStream extends OutputStream {

    static final int HEADER_SKIP_BYTES = 20;
    static final int BLOCK_HEADER_SKIP_BYTES = 8;

    private static final int HEADER_CURR_INDEX_POS = 0;

    private final File file;
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
    private long blockStartPosition;

    CappedDatabaseOutputStream(File file, int requestedSizeKb) throws IOException {
        this.file = file;
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
    }

    void startBlock() {
        long currPosition = (currIndex - lastResizeBaseIndex) % sizeBytes;
        long remainingBytes = sizeBytes - currPosition;
        if (remainingBytes < BLOCK_HEADER_SKIP_BYTES) {
            // not enough space for contiguous block header
            currIndex += remainingBytes;
        }
        blockStartIndex = currIndex;
        blockStartPosition = (currIndex - lastResizeBaseIndex) % sizeBytes;
        // make space for block size to be written at start position
        currIndex += BLOCK_HEADER_SKIP_BYTES;
    }

    long endBlock() throws IOException {
        out.seek(HEADER_SKIP_BYTES + blockStartPosition);
        out.writeLong(currIndex - blockStartIndex - BLOCK_HEADER_SKIP_BYTES);
        out.getFD().sync();
        return blockStartIndex;
    }

    boolean isOverwritten(long cappedId) {
        return cappedId < getSmallestNonOverwrittenId();
    }

    long getSmallestNonOverwrittenId() {
        // need to check lastResizeBaseIndex in case it was recently resized larger, in which case
        // currIndex - sizeBytes would be less than lastResizeBaseIndex
        return Math.max(lastResizeBaseIndex, currIndex - sizeBytes);
    }

    long getCurrIndex() {
        return currIndex;
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
        if (performEasyResize(newSizeKb)) {
            return;
        }
        long newSizeBytes = newSizeKb * 1024L;
        // keep the min of the current and new capped size
        int numKeepKb = Math.min(sizeKb, newSizeKb);
        long numKeepBytes = numKeepKb * 1024L;
        // at this point, because of the two shortcut conditionals above, currIndex must be >=
        // either the current or new capped size (numKeepBytes)
        long startPosition = convertToFilePosition(currIndex - numKeepBytes);
        lastResizeBaseIndex = currIndex - numKeepBytes;
        File tmpCappedFile = new File(file.getPath() + ".resizing.tmp");
        RandomAccessFile tmpOut = new RandomAccessFile(tmpCappedFile, "rw");
        try {
            tmpOut.writeLong(currIndex);
            tmpOut.writeInt(newSizeKb);
            tmpOut.writeLong(lastResizeBaseIndex);
            long remaining = sizeBytes - startPosition;
            out.seek(HEADER_SKIP_BYTES + startPosition);
            if (numKeepBytes > remaining) {
                copy(out, tmpOut, remaining);
                out.seek(HEADER_SKIP_BYTES);
                copy(out, tmpOut, numKeepBytes - remaining);
            } else {
                copy(out, tmpOut, numKeepBytes);
            }
            out.close();
        } finally {
            tmpOut.close();
        }
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
            throw new IOException(
                    "A single block cannot have more bytes than size of the capped database");
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

    private boolean performEasyResize(int newSizeKb) throws IOException {
        if (newSizeKb == sizeKb) {
            return true;
        }
        long newSizeBytes = newSizeKb * 1024L;
        if (isEasyResize(newSizeKb, newSizeBytes)) {
            out.seek(8);
            out.writeInt(newSizeKb);
            sizeKb = newSizeKb;
            sizeBytes = newSizeBytes;
            return true;
        }
        return false;
    }

    private boolean isEasyResize(int newSizeKb, long newSizeBytes) {
        if (newSizeKb < sizeKb && currIndex - lastResizeBaseIndex < newSizeBytes) {
            // resizing smaller and on first "loop" after a resize and haven't written up to the
            // new smaller size yet
            return true;
        }
        if (newSizeKb > sizeKb && currIndex - lastResizeBaseIndex < sizeBytes) {
            // resizing larger and on first "loop" after a resize
            return true;
        }
        return false;
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
