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
package org.glowroot.local.ui;

import java.io.IOException;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.config.PluginDescriptorCache;
import org.glowroot.jvm.HeapHistograms;
import org.glowroot.jvm.HotSpotDiagnostics;
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
    private static final boolean tryCdnForJqueryAndAngular;

    static {
        // this is primarily for use on demo site
        tryCdnForJqueryAndAngular = Boolean.getBoolean("glowroot.internal.ui.tryCdn");
    }

    private final String version;
    private final boolean aggregatesEnabled;
    private final ConfigService configService;
    private final PluginDescriptorCache pluginDescriptorCache;

    @Nullable
    private final HeapHistograms heapHistograms;
    @Nullable
    private final HotSpotDiagnostics hotSpotDiagnosticService;

    LayoutJsonService(String version, boolean aggregatesEnabled, ConfigService configService,
            PluginDescriptorCache pluginDescriptorCache, @Nullable HeapHistograms heapHistograms,
            @Nullable HotSpotDiagnostics hotSpotDiagnosticService) {
        this.version = version;
        this.aggregatesEnabled = aggregatesEnabled;
        this.configService = configService;
        this.pluginDescriptorCache = pluginDescriptorCache;
        this.heapHistograms = heapHistograms;
        this.hotSpotDiagnosticService = hotSpotDiagnosticService;
    }

    // this is only used when running under 'grunt server' and is just to get get back layout data
    // (or find out login is needed if required)
    @GET("/backend/layout")
    String getLayout() throws IOException {
        logger.debug("getAuthenticatedLayout()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("tryCdnForJqueryAndAngular", tryCdnForJqueryAndAngular);
        jg.writeBooleanField("aggregates", aggregatesEnabled);
        jg.writeBooleanField("jvmHeapHistogram", heapHistograms != null);
        jg.writeBooleanField("jvmHeapDump", hotSpotDiagnosticService != null);
        jg.writeBooleanField("jvmManageableFlags", hotSpotDiagnosticService != null);
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
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    String getUnauthenticatedLayout() throws IOException {
        logger.debug("getUnauthenticatedLayout()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("tryCdnForJqueryAndAngular", tryCdnForJqueryAndAngular);
        jg.writeBooleanField("needsAuthentication", true);
        jg.writeStringField("footerMessage", "version " + version);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }
}
