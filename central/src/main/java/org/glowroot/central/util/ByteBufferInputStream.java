/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.central.util;

import java.io.InputStream;
import java.nio.ByteBuffer;

class ByteBufferInputStream extends InputStream {

    private final ByteBuffer byteBuf;

    ByteBufferInputStream(ByteBuffer byteBuf) {
        this.byteBuf = byteBuf;
    }

    @Override
    public int read() {
        if (!byteBuf.hasRemaining()) {
            return -1;
        }
        // convert byte to int in 0..255 range
        return byteBuf.get() & 0xFF;
    }

    @Override
    public int read(byte[] bytes, int off, int len) {
        int count = Math.min(byteBuf.remaining(), len);
        if (count == 0) {
            return -1;
        }
        byteBuf.get(bytes, off, len);
        return count;
    }

    @Override
    public int available() {
        return byteBuf.remaining();
    }
}
