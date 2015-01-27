/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.collector;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;

import org.HdrHistogram.Histogram;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class LazyHistogram {

    private static final int HISTOGRAM_SIGNIFICANT_DIGITS = 2;
    private static final int MAX_VALUES = 1024;

    private long[] values = new long[8];
    private int size;
    private boolean sorted;

    private @MonotonicNonNull Histogram histogram;

    public void decodeFromByteBuffer(ByteBuffer buffer) throws DataFormatException {
        if (buffer.getInt() == 0) {
            // 0 means list of (already sorted) longs
            // read size and use it to pre-allocate correctly sized array
            int numNewValues = buffer.getInt();
            ensureCapacity(size + numNewValues);
            if (histogram == null) {
                // only will be sorted afterwards if starting out empty
                sorted = size == 0;
                while (buffer.remaining() > 0) {
                    values[size++] = buffer.getLong();
                }
            } else {
                while (buffer.remaining() > 0) {
                    histogram.recordValue(buffer.getLong());
                }
            }
        } else {
            // 1 means compressed histogram
            if (histogram == null) {
                convertValuesToHistogram();
            }
            histogram.add(Histogram.decodeFromCompressedByteBuffer(buffer, 0));
        }
    }

    public long getValueAtPercentile(double percentile) {
        if (histogram == null) {
            if (size == 0) {
                // this is consisten with HdrHistogram behavior
                return 0;
            }
            if (!sorted) {
                sortValues();
            }
            return values[(int) Math.ceil(size * percentile / 100) - 1];
        }
        return histogram.getValueAtPercentile(percentile);
    }

    public int getNeededByteBufferCapacity() {
        if (histogram == null) {
            return 8 + size * 8;
        } else {
            return 4 + histogram.getNeededByteBufferCapacity();
        }
    }

    public void encodeIntoByteBuffer(ByteBuffer targetBuffer) {
        if (histogram == null) {
            targetBuffer.putInt(0);
            // write the size so can pre-allocate correctly sized array when reading
            targetBuffer.putInt(size);
            if (!sorted) {
                // sort values before storing so don't have to sort each time later when calculating
                // percentiles
                sortValues();
            }
            for (int i = 0; i < size; i++) {
                targetBuffer.putLong(values[i]);
            }
        } else {
            targetBuffer.putInt(1);
            histogram.encodeIntoCompressedByteBuffer(targetBuffer);
        }
    }

    void add(long value) {
        ensureCapacity(size + 1);
        if (histogram != null) {
            histogram.recordValue(value);
        } else {
            values[size++] = value;
            sorted = false;
        }
    }

    private void ensureCapacity(int capacity) {
        if (histogram != null) {
            return;
        }
        if (capacity > MAX_VALUES) {
            convertValuesToHistogram();
            return;
        }
        if (capacity > values.length) {
            // at least double in size
            long[] temp = new long[Math.max(size * 2, capacity)];
            System.arraycopy(values, 0, temp, 0, size);
            values = temp;
        }
    }

    @EnsuresNonNull("histogram")
    private void convertValuesToHistogram() {
        histogram = new Histogram(HISTOGRAM_SIGNIFICANT_DIGITS);
        for (int i = 0; i < size; i++) {
            histogram.recordValue(values[i]);
        }
        values = new long[0];
    }

    private void sortValues() {
        Arrays.sort(values, 0, size);
        sorted = true;
    }
}
