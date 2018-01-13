/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.agent.live;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Longs;
import org.objectweb.asm.Type;

import org.glowroot.agent.live.JvmTool.InputStreamProcessor;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogram;

class HeapHistogramTool {

    private HeapHistogramTool() {}

    static HeapHistogram run(long pid, boolean allowAttachSelf, @Nullable File glowrootJarFile)
            throws Exception {
        return JvmTool.run(pid, "heapHisto", new HeapHistogramProcessor(), allowAttachSelf,
                glowrootJarFile);
    }

    private static class HeapHistogramProcessor implements InputStreamProcessor<HeapHistogram> {

        @Override
        public HeapHistogram process(InputStream in) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            // skip over header lines
            String line = reader.readLine();
            while (line != null && !line.contains("--------")) {
                line = reader.readLine();
            }
            if (line == null) {
                throw new IOException("Unexpected heapHisto output");
            }
            Map<String, ClassInfo> classInfos = Maps.newHashMap();
            Splitter splitter = Splitter.on(' ').omitEmptyStrings();
            while ((line = reader.readLine()) != null) {
                Iterator<String> parts = splitter.split(line).iterator();
                String num = parts.next();
                if (num.equals("Total")) {
                    break;
                }
                long count = Long.parseLong(parts.next());
                long bytes = Long.parseLong(parts.next());
                String className = parts.next();
                // skipping PermGen objects
                if (className.charAt(0) != '<') {
                    if (className.charAt(0) == '[') {
                        className = Type.getType(className).getClassName();
                    }
                    ClassInfo classInfo = classInfos.get(className);
                    if (classInfo == null) {
                        classInfo = new ClassInfo(className);
                        classInfos.put(className, classInfo);
                    }
                    classInfo.bytes += bytes;
                    classInfo.count += count;
                }
            }
            HeapHistogram.Builder builder = HeapHistogram.newBuilder();
            for (ClassInfo classInfo : ClassInfo.orderingByBytes.sortedCopy(classInfos.values())) {
                builder.addClassInfo(HeapHistogram.ClassInfo.newBuilder()
                        .setClassName(classInfo.className)
                        .setBytes(classInfo.bytes)
                        .setCount(classInfo.count));
            }
            return builder.build();
        }
    }

    private static class ClassInfo {

        private static final Ordering<ClassInfo> orderingByBytes = new Ordering<ClassInfo>() {
            @Override
            public int compare(ClassInfo left, ClassInfo right) {
                return Longs.compare(right.bytes, left.bytes);
            }
        };

        private final String className;
        private long bytes;
        private long count;

        private ClassInfo(String className) {
            this.className = className;
        }
    }
}
