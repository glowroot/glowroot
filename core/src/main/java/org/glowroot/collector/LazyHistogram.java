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

import org.HdrHistogram.Histogram;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class LazyHistogram {

    public static final int HISTOGRAM_SIGNIFICANT_DIGITS = 2;
    private static final int MAX_VALUES = 1024;

    private long[] values = new long[8];
    private int size;

    private @MonotonicNonNull Histogram histogram;

    void add(long value) {
        if (size < MAX_VALUES) {
            addToValues(value);
            return;
        }
        if (histogram == null) {
            histogram = new Histogram(HISTOGRAM_SIGNIFICANT_DIGITS);
            for (int i = 0; i < size; i++) {
                histogram.recordValue(values[i]);
            }
            values = new long[0];
        }
        histogram.recordValue(value);
    }

    int getNeededByteBufferCapacity() {
        if (histogram == null) {
            return 8 + size * 8;
        } else {
            return 8 + histogram.getNeededByteBufferCapacity();
        }
    }

    void encodeIntoByteBuffer(ByteBuffer targetBuffer) {
        if (histogram == null) {
            targetBuffer.putInt(0);
            // write the size so can pre-allocate correctly sized array when reading
            targetBuffer.putInt(size);
            // sort values before storing so don't have to sort each time later when calculating
            // percentiles
            Arrays.sort(values, 0, size);
            for (int i = 0; i < size; i++) {
                targetBuffer.putLong(values[i]);
            }
        } else {
            targetBuffer.putInt(1);
            histogram.encodeIntoCompressedByteBuffer(targetBuffer);
        }
    }

    private void addToValues(long value) {
        if (size == values.length) {
            long[] temp = new long[size * 2];
            System.arraycopy(values, 0, temp, 0, size);
            values = temp;
        }
        values[size++] = value;
    }
}
