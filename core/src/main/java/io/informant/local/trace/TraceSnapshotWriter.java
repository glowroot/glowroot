/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.trace;

import io.informant.core.util.ByteStream;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.List;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.gson.stream.JsonWriter;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceSnapshotWriter {

    private final StringBuilder sb = new StringBuilder();
    private final List<ByteStream> byteStreams = Lists.newArrayList();

    public static ByteStream toByteStream(TraceSnapshot snapshot, boolean active)
            throws UnsupportedEncodingException {
        return new TraceSnapshotWriter().toByteStreamInternal(snapshot, active);
    }

    private TraceSnapshotWriter() {}

    private ByteStream toByteStreamInternal(TraceSnapshot snapshot, boolean active)
            throws UnsupportedEncodingException {
        sb.append("{\"id\":\"");
        sb.append(snapshot.getId());
        sb.append("\",\"start\":");
        sb.append(snapshot.getStartAt());
        sb.append(",\"duration\":");
        sb.append(snapshot.getDuration());
        sb.append(",\"active\":");
        sb.append(active);
        sb.append(",\"stuck\":");
        sb.append(snapshot.isStuck());
        sb.append(",\"completed\":");
        sb.append(snapshot.isCompleted());
        sb.append(",\"background\":");
        sb.append(snapshot.isBackground());
        sb.append(",\"headline\":");
        sb.append(escapeJson(snapshot.getHeadline()));
        writeAttributes(snapshot);
        writeUserId(snapshot);
        writeError(snapshot);
        writeMetrics(snapshot);
        writeByteStream("spans", snapshot.getSpans());
        writeByteStream("coarseMergedStackTree", snapshot.getCoarseMergedStackTree());
        writeByteStream("fineMergedStackTree", snapshot.getFineMergedStackTree());
        sb.append("}");
        flushStringBuilder();
        return ByteStream.of(byteStreams);
    }

    private void writeAttributes(TraceSnapshot snapshot) {
        String attributes = snapshot.getAttributes();
        if (attributes != null) {
            sb.append(",\"attributes\":");
            sb.append(attributes);
        }
    }

    private void writeUserId(TraceSnapshot snapshot) {
        String userId = snapshot.getUserId();
        if (userId != null) {
            sb.append(",\"userId\":");
            sb.append(escapeJson(userId));
        }
    }

    private void writeError(TraceSnapshot snapshot) {
        String errorText = snapshot.getErrorText();
        if (errorText != null) {
            sb.append(",\"error\":{\"text\":");
            sb.append(escapeJson(errorText));
            if (snapshot.getErrorDetail() != null) {
                sb.append(",\"detail\":");
                sb.append(snapshot.getErrorDetail());
            }
            if (snapshot.getException() != null) {
                sb.append(",\"exception\":");
                sb.append(snapshot.getException());
            }
            sb.append("}");
        }
    }

    private void writeMetrics(TraceSnapshot snapshot) {
        String metrics = snapshot.getMetrics();
        if (metrics != null) {
            sb.append(",\"metrics\":");
            sb.append(metrics);
        }
    }

    private void writeByteStream(String attributeName, @Nullable ByteStream byteStream)
            throws UnsupportedEncodingException {
        if (byteStream != null) {
            sb.append(",\"");
            sb.append(attributeName);
            sb.append("\":");
            flushStringBuilder();
            byteStreams.add(byteStream);
        }
    }

    // flush current StringBuilder as its own chunk and reset StringBuilder
    private void flushStringBuilder() throws UnsupportedEncodingException {
        byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
        sb.setLength(0);
    }

    // this feels more performant than gson.toJson(s)
    private static String escapeJson(@Nullable String s) {
        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.setLenient(true);
        try {
            jw.value(s);
            jw.close();
            return sw.toString();
        } catch (IOException e) {
            // this can't really happen since StringWriter doesn't throw IOException
            return "error (" + e.getClass().getName() + ") occurred escaping json string";
        }
    }
}
