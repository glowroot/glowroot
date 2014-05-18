/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.config.PluginDescriptorCache;
import org.glowroot.jvm.HeapDumps;
import org.glowroot.jvm.HeapHistograms;
import org.glowroot.markers.Singleton;

/**
 * Service to read basic ui layout info.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class LayoutJsonService {

    private static final Logger logger = LoggerFactory.getLogger(LayoutJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final String version;
    private final ConfigService configService;
    private final PluginDescriptorCache pluginDescriptorCache;

    @Nullable
    private final HeapHistograms heapHistograms;
    @Nullable
    private final HeapDumps heapDumps;

    private final long fixedAggregationIntervalSeconds;

    LayoutJsonService(String version, ConfigService configService,
            PluginDescriptorCache pluginDescriptorCache, @Nullable HeapHistograms heapHistograms,
            @Nullable HeapDumps heapDumps,
            long fixedAggregationIntervalSeconds) {
        this.version = version;
        this.configService = configService;
        this.pluginDescriptorCache = pluginDescriptorCache;
        this.heapHistograms = heapHistograms;
        this.heapDumps = heapDumps;
        this.fixedAggregationIntervalSeconds = fixedAggregationIntervalSeconds;
    }

    // this is only accessed from the url when running under 'grunt server' and is just to get back
    // layout data (or find out login is needed if required)
    @GET("/backend/layout")
    String getLayout() throws IOException {
        logger.debug("getAuthenticatedLayout()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("jvmHeapHistogram", heapHistograms != null);
        jg.writeBooleanField("jvmHeapDump", heapDumps != null);
        jg.writeStringField("footerMessage", "version " + version);
        jg.writeBooleanField("passwordEnabled",
                configService.getUserInterfaceConfig().isPasswordEnabled());
        jg.writeFieldName("plugins");
        jg.writeStartArray();
        for (PluginDescriptor pluginDescriptor : pluginDescriptorCache.getPluginDescriptors()) {
            jg.writeStartObject();
            jg.writeStringField("id", pluginDescriptor.getId());
            String name = pluginDescriptor.getName();
            // by convention, strip off trailing " Plugin"
            if (name.endsWith(" Plugin")) {
                name = name.substring(0, name.lastIndexOf(" Plugin"));
            }
            jg.writeStringField("name", name);
            jg.writeEndObject();
        }
        jg.writeEndArray();
        jg.writeFieldName("traceAttributes");
        jg.writeStartArray();
        for (PluginDescriptor pluginDescriptor : pluginDescriptorCache.getPluginDescriptors()) {
            for (String traceAttribute : pluginDescriptor.getTraceAttributes()) {
                jg.writeString(traceAttribute);
            }
        }
        jg.writeEndArray();
        jg.writeNumberField("fixedAggregationIntervalSeconds", fixedAggregationIntervalSeconds);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    String getUnauthenticatedLayout() throws IOException {
        logger.debug("getUnauthenticatedLayout()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("needsAuthentication", true);
        jg.writeStringField("footerMessage", "version " + version);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }
}
