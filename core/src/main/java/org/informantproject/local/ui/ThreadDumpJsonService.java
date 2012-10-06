/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.local.ui;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to clear captured data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ThreadDumpJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(ThreadDumpJsonService.class);

    @Inject
    ThreadDumpJsonService() {}

    @JsonServiceMethod
    String getThreadDump() throws IOException {
        logger.debug("getThreadDump()");
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        List<ThreadInfo> threadInfos = Lists.newArrayList();
        long[] threadIds = threadBean.getAllThreadIds();
        // sort thread ids for consistent results across F5 refresh
        Arrays.sort(threadIds);
        for (long threadId : threadIds) {
            ThreadInfo threadInfo = threadBean.getThreadInfo(threadId, Integer.MAX_VALUE);
            if (threadInfo != null) {
                threadInfos.add(threadInfo);
            }
        }
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        for (ThreadInfo threadInfo : threadInfos) {
            jw.beginObject();
            jw.name("name");
            jw.value(threadInfo.getThreadName());
            jw.name("state");
            jw.value(threadInfo.getThreadState().name());
            jw.name("lockName");
            jw.value(threadInfo.getLockName());
            jw.name("stackTrace");
            jw.beginArray();
            for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
                jw.value(stackTraceElement.toString());
            }
            jw.endArray();
            jw.endObject();
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }
}
