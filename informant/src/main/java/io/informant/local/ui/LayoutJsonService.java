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
package io.informant.local.ui;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.ObjectMappers;
import io.informant.config.ConfigService;
import io.informant.config.PluginDescriptor;
import io.informant.config.PluginDescriptorCache;
import io.informant.jvm.Flags;
import io.informant.jvm.HeapHistograms;
import io.informant.jvm.HotSpotDiagnostic;
import io.informant.markers.Singleton;

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
    private final boolean aggregatesEnabled;
    private final ConfigService configService;
    private final PluginDescriptorCache pluginDescriptorCache;

    LayoutJsonService(String version, boolean aggregatesEnabled, ConfigService configService,
            PluginDescriptorCache pluginDescriptorCache) {
        this.version = version;
        this.aggregatesEnabled = aggregatesEnabled;
        this.configService = configService;
        this.pluginDescriptorCache = pluginDescriptorCache;
    }

    // this is only used when running under 'grunt server' and is just to get get back layout data
    // (or find out login is needed if required)
    @JsonServiceMethod
    String getLayout() throws IOException {
        logger.debug("getAuthenticatedLayout()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("aggregates", aggregatesEnabled);
        jg.writeBooleanField("jvmHeapHistogram", HeapHistograms.getAvailability().isAvailable());
        jg.writeBooleanField("jvmHeapDump", HotSpotDiagnostic.getAvailability().isAvailable());
        jg.writeBooleanField("jvmManageableFlags",
                HotSpotDiagnostic.getAvailability().isAvailable());
        jg.writeBooleanField("jvmAllFlags", Flags.getAvailability().isAvailable());
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
        jg.writeBooleanField("needsAuthentication", true);
        jg.writeStringField("footerMessage", "version " + version);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }
}
