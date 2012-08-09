/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
final class RandomAccessFiles {

    static int readFully(RandomAccessFile in, byte[] b) throws IOException {
        return readFully(in, b, 0, b.length);
    }

    static int readFully(RandomAccessFile in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int result = in.read(b, off + total, len - total);
            if (result == -1) {
                break;
            }
            total += result;
        }
        if (total != len) {
            throw new EOFException();
        }
        return total;
    }

    static void copyFully(RandomAccessFile in, RandomAccessFile out, long numBytes)
            throws IOException {

        byte[] block = new byte[1024];
        long total = 0;
        while (total < numBytes) {
            int n = in.read(block, 0, (int) Math.min(1024L, numBytes - total));
            out.write(block, 0, n);
            total += n;
        }
    }

    private RandomAccessFiles() {}
}
