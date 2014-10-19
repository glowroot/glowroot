/*
 * Copyright 2014 the original author or authors.
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
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.QueryExp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ConfigService.DuplicateMBeanObjectNameException;
import org.glowroot.config.JsonViews.UiView;
import org.glowroot.config.MBeanGauge;
import org.glowroot.jvm.LazyPlatformMBeanServer;
import org.glowroot.markers.Singleton;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

/**
 * Json service to support gauge configuration.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class MBeanGaugeJsonService {

    private static final Logger logger = LoggerFactory.getLogger(MBeanGaugeJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ConfigService configService;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

    MBeanGaugeJsonService(ConfigService configService,
            LazyPlatformMBeanServer lazyPlatformMBeanServer) {
        this.configService = configService;
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
    }

    @GET("/backend/config/mbean-gauge")
    String getMBeanGauge() throws IOException, SQLException {
        logger.debug("getMBeanGauge()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartArray();
        for (MBeanGauge mbeanGauge : configService.getMBeanGauges()) {
            writeMBeanGauge(mbeanGauge, jg, writer);
        }
        jg.writeEndArray();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/config/matching-mbean-objects")
    String getMatchingMBeanObjects(String content) throws IOException, InterruptedException {
        logger.debug("getMatchingMBeanObjects(): content={}", content);
        MBeanObjectNameRequest request =
                ObjectMappers.readRequiredValue(mapper, content, MBeanObjectNameRequest.class);
        Set<ObjectName> objectNames = lazyPlatformMBeanServer.queryNames(null,
                new ObjectNameQueryExp(request.getPartialMBeanObjectName()));
        List<String> names = Lists.newArrayList();
        for (ObjectName objectName : objectNames) {
            names.add(objectName.toString());
        }
        ImmutableList<String> sortedNames =
                Ordering.from(String.CASE_INSENSITIVE_ORDER).immutableSortedCopy(names);
        if (sortedNames.size() > request.getLimit()) {
            sortedNames = sortedNames.subList(0, request.getLimit());
        }
        return mapper.writeValueAsString(names);
    }

    @GET("/backend/config/mbean-attributes")
    String getMBeanAttributes(String content) throws IOException {
        logger.debug("getMBeanAttributes(): content={}", content);
        MBeanAttributeNamesRequest request =
                ObjectMappers.readRequiredValue(mapper, content, MBeanAttributeNamesRequest.class);
        boolean duplicateMBean = false;
        for (MBeanGauge mbeanGauge : configService.getMBeanGauges()) {
            if (mbeanGauge.getMBeanObjectName().equals(request.getMBeanObjectName())
                    && !mbeanGauge.getVersion().equals(request.getMBeanGaugeVersion())) {
                duplicateMBean = true;
                break;
            }
        }
        MBeanInfo mbeanInfo;
        try {
            mbeanInfo = getMBeanInfo(request.getMBeanObjectName());
        } catch (Exception e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return "{\"mbeanUnavailable\":true,\"duplicateMBean\":" + duplicateMBean + "}";
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("mbeanUnavailable", false);
        jg.writeBooleanField("duplicateMBean", duplicateMBean);
        jg.writeObjectField("mbeanAttributes", getAttributeNames(mbeanInfo));
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @POST("/backend/config/mbean-gauge/+")
    String addMBeanGauge(String content) throws IOException {
        logger.debug("addMBeanGauge(): content={}", content);
        MBeanGauge mbeanGauge =
                ObjectMappers.readRequiredValue(mapper, content, MBeanGauge.class);
        try {
            configService.insertMBeanGauge(mbeanGauge);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writeMBeanGauge(mbeanGauge, jg, writer);
        jg.close();
        return sb.toString();
    }

    @POST("/backend/config/mbean-gauge/([0-9a-f]+)")
    String updateMBeanGauge(String priorVersion, String content) throws IOException {
        logger.debug("updateMBeanGauge(): priorVersion={}, content={}", priorVersion, content);
        MBeanGauge mbeanGauge =
                ObjectMappers.readRequiredValue(mapper, content, MBeanGauge.class);
        configService.updateMBeanGauge(priorVersion, mbeanGauge);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writeMBeanGauge(mbeanGauge, jg, writer);
        jg.close();
        return sb.toString();
    }

    @POST("/backend/config/mbean-gauge/-")
    void removeMBeanGauge(String content) throws IOException {
        logger.debug("removeMBeanGauge(): content={}", content);
        String version = ObjectMappers.readRequiredValue(mapper, content, String.class);
        configService.deleteMBeanGauge(version);
    }

    private void writeMBeanGauge(MBeanGauge mbeanGauge, JsonGenerator jg, ObjectWriter writer)
            throws IOException {
        jg.writeStartObject();
        jg.writeFieldName("config");
        writer.writeValue(jg, mbeanGauge);
        MBeanInfo mbeanInfo = null;
        try {
            mbeanInfo = getMBeanInfo(mbeanGauge.getMBeanObjectName());
        } catch (Exception e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
        }
        jg.writeBooleanField("mbeanUnavailable", mbeanInfo == null);
        if (mbeanInfo != null) {
            jg.writeObjectField("mbeanAvailableAttributeNames",
                    getAttributeNames(mbeanInfo));
        }
        jg.writeEndObject();
    }

    private MBeanInfo getMBeanInfo(String objectName) throws Exception {
        return lazyPlatformMBeanServer.getMBeanInfo(ObjectName.getInstance(objectName));
    }

    private static List<String> getAttributeNames(MBeanInfo mbeanInfo) {
        List<String> attributeNames = Lists.newArrayList();
        for (MBeanAttributeInfo attribute : mbeanInfo.getAttributes()) {
            if (!attribute.isReadable()) {
                continue;
            }
            // only add numeric attributes
            String attributeType = attribute.getType();
            if (attributeType.equals("long") || attributeType.equals("int")
                    || attributeType.equals("double") || attributeType.equals("float")) {
                attributeNames.add(attribute.getName());
            }
        }
        return attributeNames;
    }

    private static class MBeanObjectNameRequest {

        private final String partialMBeanObjectName;
        private final int limit;

        @JsonCreator
        MBeanObjectNameRequest(
                @JsonProperty("partialMBeanObjectName") @Nullable String partialMBeanObjectName,
                @JsonProperty("limit") int limit) throws JsonMappingException {
            checkRequiredProperty(partialMBeanObjectName, "partialMBeanObjectName");
            this.partialMBeanObjectName = partialMBeanObjectName;
            this.limit = limit;
        }

        private String getPartialMBeanObjectName() {
            return partialMBeanObjectName;
        }

        private int getLimit() {
            return limit;
        }
    }

    private static class MBeanAttributeNamesRequest {

        private final String mbeanObjectName;
        private final String mbeanGaugeVersion;

        @JsonCreator
        MBeanAttributeNamesRequest(
                @JsonProperty("mbeanObjectName") @Nullable String mbeanObjectName,
                @JsonProperty("mbeanGaugeVersion") @Nullable String mbeanGaugeVersion)
                throws JsonMappingException {
            checkRequiredProperty(mbeanObjectName, "mbeanObjectName");
            checkRequiredProperty(mbeanGaugeVersion, "mbeanGaugeVersion");
            this.mbeanObjectName = mbeanObjectName;
            this.mbeanGaugeVersion = mbeanGaugeVersion;
        }

        private String getMBeanObjectName() {
            return mbeanObjectName;
        }

        private String getMBeanGaugeVersion() {
            return mbeanGaugeVersion;
        }
    }

    @SuppressWarnings("serial")
    private static class ObjectNameQueryExp implements QueryExp {

        private final String textUpper;

        private ObjectNameQueryExp(String text) {
            this.textUpper = text.toUpperCase(Locale.ENGLISH);
        }

        @Override
        public boolean apply(ObjectName name) {
            return name.toString().toUpperCase(Locale.ENGLISH).contains(textUpper);
        }

        @Override
        public void setMBeanServer(MBeanServer s) {}
    }
}
