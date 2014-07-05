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
import java.util.Locale;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.io.CharStreams;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SnapshotWriter {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private SnapshotWriter() {}

    public static String toString(Snapshot snapshot) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("id", snapshot.getId());
        jg.writeBooleanField("active", snapshot.isActive());
        jg.writeBooleanField("stuck", snapshot.isStuck());
        jg.writeNumberField("startTime", snapshot.getStartTime());
        jg.writeNumberField("captureTime", snapshot.getCaptureTime());
        jg.writeNumberField("duration", snapshot.getDuration());
        jg.writeStringField("transactionType", snapshot.getTransactionType());
        jg.writeStringField("transactionName", snapshot.getTransactionName());
        jg.writeStringField("headline", snapshot.getHeadline());
        jg.writeStringField("error", snapshot.getError());
        jg.writeStringField("user", snapshot.getUser());
        String attributes = snapshot.getAttributes();
        if (attributes != null) {
            jg.writeFieldName("attributes");
            jg.writeRawValue(attributes);
        }
        String traceMetrics = snapshot.getTraceMetrics();
        if (traceMetrics != null) {
            jg.writeFieldName("traceMetrics");
            jg.writeRawValue(traceMetrics);
        }
        String threadInfo = snapshot.getThreadInfo();
        if (threadInfo != null) {
            jg.writeFieldName("threadInfo");
            jg.writeRawValue(threadInfo);
        }
        String gcInfos = snapshot.getGcInfos();
        if (gcInfos != null) {
            jg.writeFieldName("gcInfos");
            jg.writeRawValue(gcInfos);
        }
        jg.writeStringField("spansExistence",
                snapshot.getSpansExistence().name().toLowerCase(Locale.ENGLISH));
        jg.writeStringField("coarseProfileExistence",
                snapshot.getCoarseProfileExistence().name().toLowerCase(Locale.ENGLISH));
        jg.writeStringField("fineProfileExistence",
                snapshot.getFineProfileExistence().name().toLowerCase(Locale.ENGLISH));
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }
}
