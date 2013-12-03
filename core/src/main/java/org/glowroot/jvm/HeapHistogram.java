/*
 * Copyright 2013 the original author or authors.
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
package org.glowroot.jvm;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.objectweb.asm.Type;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class HeapHistogram {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private final Map<String, ClassInfo> classInfos = Maps.newHashMap();
    private long totalBytes;
    private long totalCount;

    void addItem(String className, long bytes, long count) {
        if (className.charAt(0) == '[') {
            className = Type.getType(className).getClassName();
        }
        ClassInfo classInfo = classInfos.get(className);
        if (classInfo == null) {
            classInfos.put(className, new ClassInfo(className, bytes, count));
        } else {
            classInfo.update(bytes, count);
        }
    }

    void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    void setTotalCount(long totalCount) {
        this.totalCount = totalCount;
    }

    String toJson() throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeArrayFieldStart("items");
        List<ClassInfo> sortedClassInfos = ClassInfo.byBytesDesc.sortedCopy(classInfos.values());
        for (ClassInfo classInfo : sortedClassInfos) {
            jg.writeStartObject();
            jg.writeStringField("className", classInfo.getClassName());
            jg.writeNumberField("bytes", classInfo.getBytes());
            jg.writeNumberField("count", classInfo.getCount());
            jg.writeEndObject();
        }
        jg.writeEndArray();
        jg.writeNumberField("totalBytes", totalBytes);
        jg.writeNumberField("totalCount", totalCount);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private static class ClassInfo {

        private static final Ordering<ClassInfo> byBytesDesc = new Ordering<ClassInfo>() {
            @Override
            public int compare(ClassInfo left, ClassInfo right) {
                return Long.signum(right.bytes - left.bytes);
            }
        };

        private final String className;
        private long bytes;
        private long count;

        private ClassInfo(String className, long bytes, long count) {
            this.className = className;
            this.bytes = bytes;
            this.count = count;
        }

        private void update(long bytes, long count) {
            this.bytes += bytes;
            this.count += count;
        }

        private String getClassName() {
            return className;
        }

        private long getBytes() {
            return bytes;
        }

        private long getCount() {
            return count;
        }
    }
}
