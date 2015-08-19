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
package org.glowroot.common.repo;

import java.nio.ByteBuffer;

import org.junit.Test;

import org.glowroot.collector.spi.Histogram;

import static org.assertj.core.api.Assertions.assertThat;

public class LazyHistogramTest {

    @Test
    public void shouldTestPercentiles() {
        // test smaller numbers in more detail since that is where laziness occurs
        for (int i = 0; i < 2000; i += 10) {
            shouldTestPercentiles(i);
        }
        shouldTestPercentiles(10000);
        shouldTestPercentiles(100000);
    }

    @Test
    public void shouldTestEncodeDecode() throws Exception {
        // test smaller numbers in more detail since that is where laziness occurs
        for (int i = 0; i < 2000; i += 10) {
            shouldTestEncodeDecode(i);
        }
        shouldTestEncodeDecode(10000);
        shouldTestEncodeDecode(100000);
    }

    @Test
    public void shouldTestEncodeDecodeAndAddMore() throws Exception {
        // test smaller numbers in more detail since that is where laziness occurs
        for (int i = 0; i < 2000; i += 10) {
            shouldTestEncodeDecodeAndAddMore(i);
        }
        shouldTestEncodeDecodeAndAddMore(10000);
        shouldTestEncodeDecodeAndAddMore(100000);
    }

    @Test
    public void shouldTestDecodeOnTopOfExisting() throws Exception {
        // test smaller numbers in more detail since that is where laziness occurs
        for (int i = 0; i < 2000; i += 10) {
            shouldDecodeOnTopOfExisting(i, i);
        }
        shouldDecodeOnTopOfExisting(10000, 10000);
        shouldDecodeOnTopOfExisting(100000, 100000);

        // test smaller numbers in more detail since that is where laziness occurs
        for (int i = 0; i < 2000; i += 10) {
            shouldDecodeOnTopOfExisting(2 * i, i);
        }
        shouldDecodeOnTopOfExisting(20000, 10000);
        shouldDecodeOnTopOfExisting(200000, 100000);

        // test smaller numbers in more detail since that is where laziness occurs
        for (int i = 0; i < 2000; i += 10) {
            shouldDecodeOnTopOfExisting(i, 2 * i);
        }
        shouldDecodeOnTopOfExisting(10000, 20000);
        shouldDecodeOnTopOfExisting(100000, 200000);
    }

    @Test
    public void testResizingHistogramBetweenCompressedEncodings() {
        // given
        LazyHistogram lazyHistogram = new LazyHistogram();
        for (int i = 0; i < 2000; i++) {
            lazyHistogram.add(1);
        }
        ByteBuffer buffer = ByteBuffer.allocate(lazyHistogram.getNeededByteBufferCapacity());
        lazyHistogram.encodeIntoByteBuffer(buffer);
        // when
        lazyHistogram.add(10000);
        buffer = ByteBuffer.allocate(lazyHistogram.getNeededByteBufferCapacity());
        lazyHistogram.encodeIntoByteBuffer(buffer);
        // then
    }

    private void shouldTestPercentiles(int num) {
        // given
        LazyHistogram lazyHistogram = new LazyHistogram();
        // when
        for (int i = num; i > 0; i--) {
            lazyHistogram.add(i);
        }
        // then
        assertPercentile(lazyHistogram, num, 50);
        assertPercentile(lazyHistogram, num, 95);
        assertPercentile(lazyHistogram, num, 99);
        assertPercentile(lazyHistogram, num, 99.9);
        assertPercentile(lazyHistogram, num, 99.99);
    }

    private void shouldTestEncodeDecode(int num) throws Exception {
        // given
        LazyHistogram lazyHistogram = new LazyHistogram();
        for (int i = num; i > 0; i--) {
            lazyHistogram.add(i);
        }
        byte[] histogram = getHistogramBytes(lazyHistogram);
        lazyHistogram = new LazyHistogram();
        // when
        lazyHistogram.decodeFromByteBuffer(ByteBuffer.wrap(histogram));
        // then
        assertPercentile(lazyHistogram, num, 50);
        assertPercentile(lazyHistogram, num, 95);
        assertPercentile(lazyHistogram, num, 99);
        assertPercentile(lazyHistogram, num, 99.9);
        assertPercentile(lazyHistogram, num, 99.99);
    }

    private void shouldTestEncodeDecodeAndAddMore(int num) throws Exception {
        // given
        LazyHistogram lazyHistogram = new LazyHistogram();
        for (int i = num; i > 0; i--) {
            lazyHistogram.add(i);
        }
        byte[] histogram = getHistogramBytes(lazyHistogram);
        lazyHistogram = new LazyHistogram();
        // when
        lazyHistogram.decodeFromByteBuffer(ByteBuffer.wrap(histogram));
        for (int i = 2 * num; i > num; i--) {
            lazyHistogram.add(i);
        }
        // then
        assertPercentile(lazyHistogram, num * 2, 50);
        assertPercentile(lazyHistogram, num * 2, 95);
        assertPercentile(lazyHistogram, num * 2, 99);
        assertPercentile(lazyHistogram, num * 2, 99.9);
        assertPercentile(lazyHistogram, num * 2, 99.99);
    }

    private void shouldDecodeOnTopOfExisting(int encodedSize, int nonEncodedSize) throws Exception {
        LazyHistogram lazyHistogram = new LazyHistogram();
        for (int i = encodedSize; i > 0; i--) {
            lazyHistogram.add(i);
        }
        byte[] histogram = getHistogramBytes(lazyHistogram);
        lazyHistogram = new LazyHistogram();
        // when
        for (int i = nonEncodedSize + encodedSize; i > encodedSize; i--) {
            lazyHistogram.add(i);
        }
        lazyHistogram.decodeFromByteBuffer(ByteBuffer.wrap(histogram));
        // then
        assertPercentile(lazyHistogram, encodedSize + nonEncodedSize, 50);
        assertPercentile(lazyHistogram, encodedSize + nonEncodedSize, 95);
        assertPercentile(lazyHistogram, encodedSize + nonEncodedSize, 99);
        assertPercentile(lazyHistogram, encodedSize + nonEncodedSize, 99.9);
        assertPercentile(lazyHistogram, encodedSize + nonEncodedSize, 99.99);
    }

    private void assertPercentile(LazyHistogram lazyHistogram, int num, double percentile) {
        long low = (long) Math.floor(num * percentile * 0.99 / 100);
        long high = (long) Math.ceil(num * percentile * 1.01 / 100);
        assertThat(lazyHistogram.getValueAtPercentile(percentile)).isBetween(low, high);
    }

    private static byte[] getHistogramBytes(Histogram histogram) {
        ByteBuffer buffer = ByteBuffer.allocate(histogram.getNeededByteBufferCapacity());
        buffer.clear();
        histogram.encodeIntoByteBuffer(buffer);
        int size = buffer.position();
        buffer.flip();
        byte[] histogramBytes = new byte[size];
        buffer.get(histogramBytes, 0, size);
        return histogramBytes;
    }
}
