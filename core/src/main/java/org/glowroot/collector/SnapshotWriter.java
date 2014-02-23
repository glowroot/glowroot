/*
 * Copyright 2012-2014 the original author or authors.
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
import java.io.UnsupportedEncodingException;
import java.util.List;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;

import org.glowroot.markers.OnlyUsedByTests;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SnapshotWriter {

    private final StringBuilder sb = new StringBuilder();
    private final List</*@ReadOnly*/CharSource> charSources = Lists.newArrayList();

    @ReadOnly
    public static CharSource toCharSource(Snapshot snapshot, boolean summary)
            throws UnsupportedEncodingException {
        return new SnapshotWriter().toCharSourceInternal(snapshot, summary);
    }

    private SnapshotWriter() {}

    @ReadOnly
    private CharSource toCharSourceInternal(Snapshot snapshot, boolean summary)
            throws UnsupportedEncodingException {
        sb.append("{\"id\":\"");
        sb.append(snapshot.getId());
        sb.append("\",\"active\":");
        sb.append(snapshot.isActive());
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
        sb.append(",\"transactionName\":\"");
        sb.append(JsonStringEncoder.getInstance().quoteAsString(snapshot.getTransactionName()));
        sb.append("\"");
        sb.append(",\"headline\":\"");
        sb.append(JsonStringEncoder.getInstance().quoteAsString(snapshot.getHeadline()));
        sb.append("\"");
        writeErrorMessage(snapshot);
        writeAttributes(snapshot);
        writeUser(snapshot);
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
        return CharSource.concat(charSources);
    }

    private void writeErrorMessage(Snapshot snapshot) {
        String error = snapshot.getError();
        if (error != null) {
            sb.append(",\"error\":\"");
            sb.append(JsonStringEncoder.getInstance().quoteAsString(error));
            sb.append("\"");
        }
    }

    private void writeAttributes(Snapshot snapshot) {
        String attributes = snapshot.getAttributes();
        if (attributes != null) {
            sb.append(",\"attributes\":");
            sb.append(attributes);
        }
    }

    private void writeUser(Snapshot snapshot) {
        String user = snapshot.getUser();
        if (user != null) {
            sb.append(",\"user\":\"");
            sb.append(JsonStringEncoder.getInstance().quoteAsString(user));
            sb.append("\"");
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
        charSources.add(CharSource.wrap(sb.toString()));
        sb.setLength(0);
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    public static String toString(Snapshot snapshot, boolean summary) throws IOException {
        return toCharSource(snapshot, summary).read();
    }
}
