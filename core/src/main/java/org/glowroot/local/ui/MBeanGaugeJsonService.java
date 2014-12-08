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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.QueryExp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.immutables.common.marshal.Marshaling;
import org.immutables.value.Json;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Marshaling2;
import org.glowroot.common.ObjectMappers;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ConfigService.DuplicateMBeanObjectNameException;
import org.glowroot.config.ImmutableMBeanGauge;
import org.glowroot.config.MBeanGauge;
import org.glowroot.jvm.LazyPlatformMBeanServer;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class MBeanGaugeJsonService {

    private static final Logger logger = LoggerFactory.getLogger(MBeanGaugeJsonService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConfigService configService;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

    MBeanGaugeJsonService(ConfigService configService,
            LazyPlatformMBeanServer lazyPlatformMBeanServer) {
        this.configService = configService;
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
    }

    @GET("/backend/config/mbean-gauges")
    String getMBeanGauge() {
        List<MBeanGaugeResponse> responses = Lists.newArrayList();
        for (MBeanGauge mbeanGauge : configService.getMBeanGauges()) {
            responses.add(buildResponse(mbeanGauge));
        }
        return Marshaling2.toJson(responses, MBeanGaugeResponse.class);
    }

    @GET("/backend/config/matching-mbean-objects")
    String getMatchingMBeanObjects(String queryString) throws Exception {
        MBeanObjectNameRequest request =
                QueryStrings.decode(queryString, MBeanObjectNameRequest.class);
        Set<ObjectName> objectNames = lazyPlatformMBeanServer.queryNames(null,
                new ObjectNameQueryExp(request.partialMBeanObjectName()));
        List<String> names = Lists.newArrayList();
        for (ObjectName objectName : objectNames) {
            names.add(objectName.toString());
        }
        ImmutableList<String> sortedNames =
                Ordering.from(String.CASE_INSENSITIVE_ORDER).immutableSortedCopy(names);
        if (sortedNames.size() > request.limit()) {
            sortedNames = sortedNames.subList(0, request.limit());
        }
        return mapper.writeValueAsString(names);
    }

    @GET("/backend/config/mbean-attributes")
    String getMBeanAttributes(String queryString) throws Exception {
        MBeanAttributeNamesRequest request =
                QueryStrings.decode(queryString, MBeanAttributeNamesRequest.class);
        ImmutableMBeanAttributeNamesResponse.Builder builder =
                ImmutableMBeanAttributeNamesResponse.builder();
        for (MBeanGauge mbeanGauge : configService.getMBeanGauges()) {
            if (mbeanGauge.mbeanObjectName().equals(request.mbeanObjectName())
                    && !mbeanGauge.version().equals(request.mbeanGaugeVersion())) {
                builder.duplicateMBean(true);
                break;
            }
        }
        MBeanInfo mbeanInfo;
        try {
            mbeanInfo = getMBeanInfo(request.mbeanObjectName());
        } catch (Exception e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return Marshaling2.toJson(builder.mbeanUnavailable(true).build());
        }
        builder.addAllMbeanAttributes(getAttributeNames(mbeanInfo));
        return Marshaling2.toJson(builder.build());
    }

    @POST("/backend/config/mbean-gauges/add")
    String addMBeanGauge(String content) throws IOException {
        MBeanGaugeDto mbeanGaugeDto = Marshaling.fromJson(content, MBeanGaugeDto.class);
        MBeanGauge mbeanGauge = mbeanGaugeDto.toConfig();
        try {
            configService.insertMBeanGauge(mbeanGauge);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return Marshaling2.toJson(buildResponse(mbeanGauge));
    }

    @POST("/backend/config/mbean-gauges/update")
    String updateMBeanGauge(String content) throws IOException {
        MBeanGaugeDto mbeanGaugeDto = Marshaling.fromJson(content, MBeanGaugeDto.class);
        MBeanGauge mbeanGauge = mbeanGaugeDto.toConfig();
        String version = mbeanGaugeDto.version();
        if (version == null) {
            throw new IllegalArgumentException("Missing required request property: version");
        }
        configService.updateMBeanGauge(mbeanGauge, version);
        return Marshaling2.toJson(buildResponse(mbeanGauge));
    }

    @POST("/backend/config/mbean-gauges/remove")
    void removeMBeanGauge(String content) throws IOException {
        String version = ObjectMappers.readRequiredValue(mapper, content, String.class);
        configService.deleteMBeanGauge(version);
    }

    private MBeanGaugeResponse buildResponse(MBeanGauge mbeanGauge) {
        MBeanInfo mbeanInfo = null;
        try {
            mbeanInfo = getMBeanInfo(mbeanGauge.mbeanObjectName());
        } catch (Exception e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
        }
        ImmutableMBeanGaugeResponse.Builder builder = ImmutableMBeanGaugeResponse.builder()
                .config(MBeanGaugeDto.fromConfig(mbeanGauge));
        if (mbeanInfo == null) {
            builder.mbeanUnavailable(true);
        } else {
            builder.addAllMbeanAvailableAttributeNames(getAttributeNames(mbeanInfo));
        }
        return builder.build();
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

    @Value.Immutable
    @Json.Marshaled
    abstract static class MBeanObjectNameRequest {
        abstract String partialMBeanObjectName();
        abstract int limit();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class MBeanAttributeNamesRequest {
        abstract String mbeanObjectName();
        abstract @Nullable String mbeanGaugeVersion();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class MBeanAttributeNamesResponse {
        @Value.Default
        boolean mbeanUnavailable() {
            return false;
        }
        @Value.Default
        boolean duplicateMBean() {
            return false;
        }
        abstract List<String> mbeanAttributes();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class MBeanGaugeResponse {
        abstract MBeanGaugeDto config();
        @Value.Default
        boolean mbeanUnavailable() {
            return false;
        }
        abstract List<String> mbeanAvailableAttributeNames();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class MBeanGaugeDto {

        abstract String name();
        abstract String mbeanObjectName();
        abstract List<String> mbeanAttributeNames();
        abstract @Nullable String version(); // null for insert operations

        private static MBeanGaugeDto fromConfig(MBeanGauge config) {
            return ImmutableMBeanGaugeDto.builder()
                    .name(config.name())
                    .mbeanObjectName(config.mbeanObjectName())
                    .addAllMbeanAttributeNames(config.mbeanAttributeNames())
                    .version(config.version())
                    .build();
        }

        private MBeanGauge toConfig() {
            return ImmutableMBeanGauge.builder()
                    .name(name())
                    .mbeanObjectName(mbeanObjectName())
                    .addAllMbeanAttributeNames(mbeanAttributeNames())
                    .build();
        }
    }
}
