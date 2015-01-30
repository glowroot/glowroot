/*
 * Copyright 2012-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.io.CharStreams;

public class TraceWriter {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private TraceWriter() {}

    public static String toString(Trace trace) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("id", trace.id());
        jg.writeBooleanField("active", trace.active());
        jg.writeBooleanField("partial", trace.partial());
        jg.writeNumberField("startTime", trace.startTime());
        jg.writeNumberField("captureTime", trace.captureTime());
        jg.writeNumberField("duration", trace.duration());
        jg.writeStringField("transactionType", trace.transactionType());
        jg.writeStringField("transactionName", trace.transactionName());
        jg.writeStringField("headline", trace.headline());
        jg.writeStringField("error", trace.error());
        jg.writeStringField("user", trace.user());
        String customAttributes = trace.customAttributes();
        if (customAttributes != null) {
            jg.writeFieldName("customAttributes");
            jg.writeRawValue(customAttributes);
        }
        String customDetail = trace.customDetail();
        if (customDetail != null) {
            jg.writeFieldName("customDetail");
            jg.writeRawValue(customDetail);
        }
        String metrics = trace.metrics();
        if (metrics != null) {
            jg.writeFieldName("metrics");
            jg.writeRawValue(metrics);
        }
        writeOptionalNumberField(jg, "threadCpuTime", trace.threadCpuTime());
        writeOptionalNumberField(jg, "threadBlockedTime", trace.threadBlockedTime());
        writeOptionalNumberField(jg, "threadWaitedTime", trace.threadWaitedTime());
        writeOptionalNumberField(jg, "threadAllocatedBytes", trace.threadAllocatedBytes());
        String gcInfos = trace.gcInfos();
        if (gcInfos != null) {
            jg.writeFieldName("gcInfos");
            jg.writeRawValue(gcInfos);
        }
        jg.writeNumberField("entryCount", trace.entryCount());
        jg.writeNumberField("profileSampleCount", trace.profileSampleCount());
        jg.writeStringField("entriesExistence",
                trace.entriesExistence().name().toLowerCase(Locale.ENGLISH));
        jg.writeStringField("profileExistence",
                trace.profileExistence().name().toLowerCase(Locale.ENGLISH));
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private static void writeOptionalNumberField(JsonGenerator jg, String fieldName,
            @Nullable Long value) throws IOException {
        if (value != null) {
            jg.writeNumberField(fieldName, value);
        }
    }
}
