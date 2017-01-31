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
package org.glowroot.ui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.io.CharSource;

public abstract class ChunkSource {

    public abstract ChunkCopier getCopier(Writer writer) throws IOException;

    static ChunkSource create(final CharSource charSource) {
        return new ChunkSource() {
            @Override
            public ChunkCopier getCopier(Writer writer) throws IOException {
                return new ReaderChunkCopier(charSource.openStream(), writer);
            }
        };
    }

    static ChunkSource wrap(final String str) {
        return new ChunkSource() {
            @Override
            public ChunkCopier getCopier(Writer writer) throws IOException {
                return new StringChunkCopier(str, writer);
            }
        };
    }

    static ChunkSource concat(final List<ChunkSource> chunkSources) {
        return new ChunkSource() {
            @Override
            public ChunkCopier getCopier(Writer writer) throws IOException {
                return new ConcatChunkCopier(chunkSources, writer);
            }
        };
    }

    public interface ChunkCopier {

        // returns false when nothing left to copy
        boolean copyNext() throws IOException;
    }

    private static class ReaderChunkCopier implements ChunkCopier {

        private static final int CHUNK_SIZE = 8192;

        private final Reader reader;
        private final Writer writer;

        private final char[] buffer = new char[CHUNK_SIZE];

        private ReaderChunkCopier(Reader reader, Writer writer) {
            this.reader = reader;
            this.writer = writer;
        }

        @Override
        public boolean copyNext() throws IOException {
            int total = readFully(reader, buffer);
            if (total == 0) {
                return false;
            }
            writer.write(buffer, 0, total);
            return true;
        }

        private static int readFully(Reader reader, char[] buffer) throws IOException {
            int total = 0;
            while (true) {
                int n = reader.read(buffer, total, buffer.length - total);
                if (n == -1) {
                    break;
                }
                total += n;
                if (total == buffer.length) {
                    break;
                }
            }
            return total;
        }
    }

    private static class ConcatChunkCopier implements ChunkCopier {

        private final Iterator<ChunkSource> chunkSources;
        private final Writer writer;

        private volatile @Nullable ChunkCopier currChunkCopier;

        private ConcatChunkCopier(Iterable<ChunkSource> chunkSources, Writer writer) {
            this.chunkSources = chunkSources.iterator();
            this.writer = writer;
        }

        @Override
        public boolean copyNext() throws IOException {
            if (currChunkCopier == null) {
                if (!chunkSources.hasNext()) {
                    return false;
                }
                // advance to the first chunk source
                currChunkCopier = chunkSources.next().getCopier(writer);
            }
            if (currChunkCopier.copyNext()) {
                return true;
            }
            if (!chunkSources.hasNext()) {
                return false;
            }
            // advance to the next chunk source
            currChunkCopier = chunkSources.next().getCopier(writer);
            return copyNext();
        }
    }

    private static class StringChunkCopier implements ChunkCopier {

        private final String str;
        private final Writer writer;

        private volatile boolean closed;

        private StringChunkCopier(String str, Writer writer) {
            this.str = str;
            this.writer = writer;
        }

        @Override
        public boolean copyNext() throws IOException {
            if (closed) {
                return false;
            }
            writer.write(str);
            closed = true;
            return true;
        }
    }
}
