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

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.common.ChunkSource;
import org.glowroot.common.ChunkSource.ChunkCopier;
import org.glowroot.transaction.model.QueryData;

import static com.google.common.base.Preconditions.checkNotNull;

public class QueriesChunkSourceCreator {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private QueriesChunkSourceCreator() {}

    public static @Nullable ChunkSource createQueriesChunkSource(
            Map<String, Map<String, QueryData>> queries) throws JsonProcessingException {
        if (queries.isEmpty()) {
            return null;
        }
        return new QueriesChunkSource(queries);
    }

    private static class QueriesChunkSource extends ChunkSource {

        private final Map<String, Map<String, QueryData>> queries;

        private QueriesChunkSource(Map<String, Map<String, QueryData>> queries) {
            this.queries = queries;
        }

        @Override
        public ChunkCopier getCopier(Writer writer) throws IOException {
            return new QueriesChunkCopier(queries, writer);
        }
    }

    private static class QueriesChunkCopier implements ChunkCopier {

        private final Iterator<Entry<String, Map<String, QueryData>>> outerIterator;
        private final JsonGenerator jg;

        private @MonotonicNonNull String currQueryType;
        private @MonotonicNonNull Iterator<Entry<String, QueryData>> currIterator;
        private boolean closed;

        private QueriesChunkCopier(Map<String, Map<String, QueryData>> queries, Writer writer)
                throws IOException {
            this.outerIterator = queries.entrySet().iterator();
            jg = jsonFactory.createGenerator(writer);
            jg.writeStartArray();
        }

        @Override
        public boolean copyNext() throws IOException {
            if (closed) {
                return false;
            }
            if (currIterator == null || !currIterator.hasNext()) {
                if (outerIterator.hasNext()) {
                    Entry<String, Map<String, QueryData>> next = outerIterator.next();
                    currQueryType = next.getKey();
                    currIterator = next.getValue().entrySet().iterator();
                    return copyNext();
                }
                jg.writeEndArray();
                jg.flush();
                closed = true;
                return true;
            }
            // currQueryType is only null at the very beginning when currIterator is also null
            checkNotNull(currQueryType);
            Entry<String, QueryData> query = currIterator.next();
            query.getValue().writeValue(currQueryType, query.getKey(), jg);
            jg.flush();
            return true;
        }
    }
}
