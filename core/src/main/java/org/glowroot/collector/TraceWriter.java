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
public class TraceWriter {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private TraceWriter() {}

    public static String toString(Trace trace) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("id", trace.getId());
        jg.writeBooleanField("active", trace.isActive());
        jg.writeBooleanField("partial", trace.isPartial());
        jg.writeNumberField("startTime", trace.getStartTime());
        jg.writeNumberField("captureTime", trace.getCaptureTime());
        jg.writeNumberField("duration", trace.getDuration());
        jg.writeStringField("transactionType", trace.getTransactionType());
        jg.writeStringField("transactionName", trace.getTransactionName());
        jg.writeStringField("headline", trace.getHeadline());
        jg.writeStringField("error", trace.getError());
        jg.writeStringField("user", trace.getUser());
        String customAttributes = trace.getCustomAttributes();
        if (customAttributes != null) {
            jg.writeFieldName("customAttributes");
            jg.writeRawValue(customAttributes);
        }
        String metrics = trace.getMetrics();
        if (metrics != null) {
            jg.writeFieldName("metrics");
            jg.writeRawValue(metrics);
        }
        String threadInfo = trace.getThreadInfo();
        if (threadInfo != null) {
            jg.writeFieldName("threadInfo");
            jg.writeRawValue(threadInfo);
        }
        String gcInfos = trace.getGcInfos();
        if (gcInfos != null) {
            jg.writeFieldName("gcInfos");
            jg.writeRawValue(gcInfos);
        }
        jg.writeStringField("entriesExistence",
                trace.getEntriesExistence().name().toLowerCase(Locale.ENGLISH));
        jg.writeStringField("profileExistence",
                trace.getProfileExistence().name().toLowerCase(Locale.ENGLISH));
        jg.writeStringField("outlierProfileExistence",
                trace.getOutlierProfileExistence().name().toLowerCase(Locale.ENGLISH));
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }
}
