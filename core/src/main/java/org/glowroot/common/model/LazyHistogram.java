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
package org.glowroot.common.model;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import org.HdrHistogram.Histogram;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.collector.spi.model.AggregateOuterClass.Aggregate;

public class LazyHistogram {

    private static final int HISTOGRAM_SIGNIFICANT_DIGITS = 5;
    private static final int MAX_VALUES = 1024;

    private long[] values = new long[8];
    private int size;
    private boolean sorted;

    private @MonotonicNonNull Histogram histogram;

    public Aggregate.Histogram toProtobuf(ScratchBuffer scratchBuffer) {
        Aggregate.Histogram.Builder builder = Aggregate.Histogram.newBuilder();
        if (histogram == null) {
            if (!sorted) {
                // sort values before storing so don't have to sort each time later when calculating
                // percentiles
                sortValues();
            }
            for (int i = 0; i < size; i++) {
                builder.addOrderedRawValue(values[i]);
            }
        } else {
            ByteBuffer buffer = scratchBuffer.getBuffer(histogram.getNeededByteBufferCapacity());
            buffer.clear();
            histogram.encodeIntoByteBuffer(buffer);
            int size = buffer.position();
            buffer.flip();
            builder.setEncodedBytes(ByteString.copyFrom(buffer, size));
        }
        return builder.build();
    }

    public void merge(Aggregate.Histogram toBeMergedHistogram) throws DataFormatException {
        ByteString encodedBytes = toBeMergedHistogram.getEncodedBytes();
        if (encodedBytes.isEmpty()) {
            for (long rawValue : toBeMergedHistogram.getOrderedRawValueList()) {
                add(rawValue);
            }
        } else {
            if (histogram == null) {
                convertValuesToHistogram();
            }
            histogram.add(Histogram.decodeFromByteBuffer(encodedBytes.asReadOnlyByteBuffer(), 0));
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

    @VisibleForTesting
    public void add(long value) {
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
        // tracking nanoseconds, but only at microsecond precision (to save histogram space)
        histogram = new Histogram(1000, 2000, HISTOGRAM_SIGNIFICANT_DIGITS);
        histogram.setAutoResize(true);
        for (int i = 0; i < size; i++) {
            histogram.recordValue(values[i]);
        }
        values = new long[0];
    }

    private void sortValues() {
        Arrays.sort(values, 0, size);
        sorted = true;
    }

    public static class ScratchBuffer {

        private @MonotonicNonNull ByteBuffer buffer;

        ByteBuffer getBuffer(int capacity) {
            if (buffer == null || buffer.capacity() < capacity) {
                buffer = ByteBuffer.allocate(capacity);
            }
            return buffer;
        }
    }
}
