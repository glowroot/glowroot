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
import java.lang.management.ManagementFactory;

import javax.management.JMException;
import javax.management.ObjectName;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.ObjectMappers;
import io.informant.markers.Singleton;

/**
 * Json service to read basic ui layout info, bound to /backend/layout.
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

    LayoutJsonService(String version, boolean aggregatesEnabled) {
        this.version = version;
        this.aggregatesEnabled = aggregatesEnabled;
    }

    @JsonServiceMethod
    String getLayout() throws IOException, JMException {
        logger.debug("getLayout()");
        boolean hotSpotDiagnosticMBeanAvailable = isHotSpotDiagnosticMBeanAvailable();
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("aggregates", aggregatesEnabled);
        jg.writeBooleanField("jvmHeapDump", hotSpotDiagnosticMBeanAvailable);
        jg.writeBooleanField("jvmDiagnosticOptions", hotSpotDiagnosticMBeanAvailable);
        jg.writeBooleanField("jvmAllOptions", hotSpotDiagnosticMBeanAvailable);
        jg.writeStringField("footerMessage", "version " + version);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private boolean isHotSpotDiagnosticMBeanAvailable() {
        try {
            ObjectName hotSpotDiagnostic =
                    ObjectName.getInstance("com.sun.management:type=HotSpotDiagnostic");
            ManagementFactory.getPlatformMBeanServer().getObjectInstance(hotSpotDiagnostic);
            return true;
        } catch (JMException e) {
            return false;
        }
    }
}
