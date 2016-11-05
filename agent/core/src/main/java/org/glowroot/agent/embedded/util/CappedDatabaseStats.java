/*
 * Copyright 2015-2016 the original author or authors.
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

public class CappedDatabaseStats {

    private long totalBytesBeforeCompression;
    private long totalBytesAfterCompression;
    private long totalNanos;
    private long writeCount;

    public long getTotalBytesBeforeCompression() {
        return totalBytesBeforeCompression;
    }

    public long getTotalBytesAfterCompression() {
        return totalBytesAfterCompression;
    }

    public double getTotalMillis() {
        return totalNanos / 1000000.0;
    }

    public long getWriteCount() {
        return writeCount;
    }

    public double getCompressionRatio() {
        return (totalBytesBeforeCompression - totalBytesAfterCompression)
                / (double) totalBytesBeforeCompression;
    }

    public double getAverageBytesPerWriteBeforeCompression() {
        return totalBytesBeforeCompression / (double) writeCount;
    }

    public double getAverageBytesPerWriteAfterCompression() {
        return totalBytesAfterCompression / (double) writeCount;
    }

    public double getAverageMillisPerWrite() {
        return totalNanos / (1000 * 1000 * (double) writeCount);
    }

    void record(long bytesBeforeCompression, long bytesAfterCompression, long nanos) {
        totalBytesBeforeCompression += bytesBeforeCompression;
        totalBytesAfterCompression += bytesAfterCompression;
        totalNanos += nanos;
        writeCount++;
    }
}
