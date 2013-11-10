/*
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
package io.informant.collector;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;

import io.informant.common.CharStreams2;
import io.informant.markers.OnlyUsedByTests;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SnapshotWriter {

    private final StringBuilder sb = new StringBuilder();
    private final List</*@ReadOnly*/CharSource> charSources = Lists.newArrayList();

    @ReadOnly
    public static CharSource toCharSource(Snapshot snapshot, boolean active, boolean summary)
            throws UnsupportedEncodingException {
        return new SnapshotWriter().toCharSourceInternal(snapshot, active, summary);
    }

    private SnapshotWriter() {}

    @ReadOnly
    private CharSource toCharSourceInternal(Snapshot snapshot, boolean active, boolean summary)
            throws UnsupportedEncodingException {
        sb.append("{\"id\":\"");
        sb.append(snapshot.getId());
        sb.append("\",\"active\":");
        sb.append(active);
        sb.append(",\"stuck\":");
        sb.append(snapshot.isStuck());
        sb.append(",\"startTime\":");
        sb.append(snapshot.getStartTime());
        sb.append(",\"captureTime\":");
        sb.append(snapshot.getCaptureTime());
        sb.append(",\"duration\":");
        sb.append(snapshot.getDuration());
        sb.append(",\"background\":");
        sb.append(snapshot.isBackground());
        sb.append(",\"grouping\":\"");
        sb.append(JsonStringEncoder.getInstance().quoteAsString(snapshot.getGrouping()));
        sb.append("\"");
        writeAttributes(snapshot);
        writeUserId(snapshot);
        writeError(snapshot);
        writeMetrics(snapshot);
        sb.append(",\"jvmInfo\":");
        sb.append(snapshot.getJvmInfo());
        writeCharSource("spans", snapshot.getSpans());
        writeCharSource("coarseMergedStackTree", snapshot.getCoarseMergedStackTree());
        writeCharSource("fineMergedStackTree", snapshot.getFineMergedStackTree());
        if (summary) {
            sb.append(",\"summary\":true");
        }
        sb.append("}");
        flushStringBuilder();
        return CharStreams2.join(charSources);
    }

    private void writeAttributes(Snapshot snapshot) {
        String attributes = snapshot.getAttributes();
        if (attributes != null) {
            sb.append(",\"attributes\":");
            sb.append(attributes);
        }
    }

    private void writeUserId(Snapshot snapshot) {
        String userId = snapshot.getUserId();
        if (userId != null) {
            sb.append(",\"userId\":\"");
            sb.append(JsonStringEncoder.getInstance().quoteAsString(userId));
            sb.append("\"");
        }
    }

    private void writeError(Snapshot snapshot) {
        String errorText = snapshot.getErrorText();
        if (errorText != null) {
            sb.append(",\"error\":{\"text\":\"");
            sb.append(JsonStringEncoder.getInstance().quoteAsString(errorText));
            sb.append("\"");
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

    private void writeMetrics(Snapshot snapshot) {
        String metrics = snapshot.getMetrics();
        if (metrics != null) {
            sb.append(",\"metrics\":");
            sb.append(metrics);
        }
    }

    private void writeCharSource(String attributeName, @ReadOnly @Nullable CharSource charSource)
            throws UnsupportedEncodingException {
        if (charSource != null) {
            sb.append(",\"");
            sb.append(attributeName);
            sb.append("\":");
            flushStringBuilder();
            charSources.add(charSource);
        }
    }

    // flush current StringBuilder as its own chunk and reset StringBuilder
    private void flushStringBuilder() throws UnsupportedEncodingException {
        charSources.add(CharStreams.asCharSource(sb.toString()));
        sb.setLength(0);
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    public static String toString(Snapshot snapshot, boolean active, boolean summary)
            throws IOException {
        return toCharSource(snapshot, active, summary).read();
    }
}
