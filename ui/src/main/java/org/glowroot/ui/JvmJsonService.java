/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.ui;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveJvmService.MBeanTreeRequest;
import org.glowroot.common.util.ObjectMappers;

@JsonService
class JvmJsonService {

    private static final Logger logger = LoggerFactory.getLogger(JvmJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final LiveJvmService liveJvmService;

    JvmJsonService(LiveJvmService liveJvmService) {
        this.liveJvmService = liveJvmService;
    }

    @GET("/backend/jvm/mbean-tree")
    String getMBeanTree(String queryString) throws Exception {
        MBeanTreeRequest request = QueryStrings.decode(queryString, MBeanTreeRequest.class);
        return mapper.writeValueAsString(liveJvmService.getMBeanTree(request));
    }

    @GET("/backend/jvm/mbean-attribute-map")
    String getMBeanAttributeMap(String queryString) throws Exception {
        MBeanAttributeMapRequest request =
                QueryStrings.decode(queryString, MBeanAttributeMapRequest.class);
        return mapper.writeValueAsString(liveJvmService
                .getMBeanSortedAttributeMap(request.serverId(), request.objectName()));
    }

    @POST("/backend/jvm/perform-gc")
    void performGC() throws IOException {
        liveJvmService.gc();
    }

    @GET("/backend/jvm/thread-dump")
    String getThreadDump() throws IOException {
        return mapper.writeValueAsString(liveJvmService.getAllThreads());
    }

    @GET("/backend/jvm/heap-dump-default-dir")
    String getHeapDumpDefaultDir(String queryString) throws Exception {
        String serverId = getServerId(queryString);
        return mapper.writeValueAsString(liveJvmService.getHeapDumpDefaultDirectory(serverId));
    }

    @POST("/backend/jvm/available-disk-space")
    String getAvailableDiskSpace(String content) throws IOException {
        HeapDumpRequest request = mapper.readValue(content, ImmutableHeapDumpRequest.class);
        try {
            return Long.toString(
                    liveJvmService.getAvailableDiskSpace(request.serverId(), request.directory()));
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            // this is for specific common errors, e.g. "Directory doesn't exist"
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("error", e.getMessage());
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
    }

    @POST("/backend/jvm/dump-heap")
    String dumpHeap(String content) throws Exception {
        HeapDumpRequest request = mapper.readValue(content, ImmutableHeapDumpRequest.class);
        try {
            return mapper.writeValueAsString(
                    liveJvmService.dumpHeap(request.serverId(), request.directory()));
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            // this is for specific common errors, e.g. "Directory doesn't exist"
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("error", e.getMessage());
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
    }

    @GET("/backend/jvm/process-info")
    String getProcessInfo(String queryString) throws Exception {
        String serverId = getServerId(queryString);
        return mapper.writeValueAsString(liveJvmService.getProcessInfo(serverId));
    }

    @GET("/backend/jvm/capabilities")
    String getCapabilities(String queryString) throws Exception {
        String serverId = getServerId(queryString);
        return mapper.writeValueAsString(liveJvmService.getCapabilities(serverId));
    }

    private static String getServerId(String queryString) throws Exception {
        return queryString.substring("server-id".length() + 1);
    }

    @Value.Immutable
    interface MBeanAttributeMapRequest {
        String serverId();
        String objectName();
    }

    @Value.Immutable
    interface HeapDumpRequest {
        String serverId();
        String directory();
    }
}
