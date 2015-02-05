/*
 * Copyright 2014-2015 the original author or authors.
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
import javax.management.Descriptor;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.common.marshal.Marshaling;
import org.immutables.value.Json;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Marshaling2;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ConfigService.DuplicateMBeanObjectNameException;
import org.glowroot.config.GaugeConfig;
import org.glowroot.config.ImmutableGaugeConfig;
import org.glowroot.jvm.LazyPlatformMBeanServer;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class GaugeJsonService {

    private static final Logger logger = LoggerFactory.getLogger(GaugeJsonService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConfigService configService;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

    GaugeJsonService(ConfigService configService,
            LazyPlatformMBeanServer lazyPlatformMBeanServer) {
        this.configService = configService;
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
    }

    @GET("/backend/config/gauges")
    String getGaugeConfigs() {
        List<GaugeConfigWithWarningMessages> responses = Lists.newArrayList();
        List<GaugeConfig> gaugeConfigs = configService.getGaugeConfigs();
        gaugeConfigs = GaugeConfig.orderingByName.immutableSortedCopy(gaugeConfigs);
        for (GaugeConfig gaugeConfig : gaugeConfigs) {
            responses.add(ImmutableGaugeConfigWithWarningMessages.builder()
                    .config(GaugeConfigDto.fromConfig(gaugeConfig))
                    .build());
        }
        return Marshaling2.toJson(responses, GaugeConfigWithWarningMessages.class);
    }

    @GET("/backend/config/gauges/([0-9a-f]{40})")
    String getGaugeConfig(String version) {
        GaugeConfig gaugeConfig = configService.getGaugeConfig(version);
        if (gaugeConfig == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        return Marshaling2.toJson(buildResponse(gaugeConfig));
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
        return mapper.writeValueAsString(sortedNames);
    }

    @GET("/backend/config/mbean-attributes")
    String getMBeanAttributes(String queryString) throws Exception {
        MBeanAttributeNamesRequest request =
                QueryStrings.decode(queryString, MBeanAttributeNamesRequest.class);
        ImmutableMBeanAttributeNamesResponse.Builder builder =
                ImmutableMBeanAttributeNamesResponse.builder();
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            if (gaugeConfig.mbeanObjectName().equals(request.mbeanObjectName())
                    && !gaugeConfig.version().equals(request.gaugeVersion())) {
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

    @POST("/backend/config/gauges/add")
    String addGauge(String content) throws Exception {
        GaugeConfigDto gaugeConfigDto = Marshaling.fromJson(content, GaugeConfigDto.class);
        GaugeConfig gaugeConfig = gaugeConfigDto.toConfig();
        try {
            configService.insertGaugeConfig(gaugeConfig);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return Marshaling2.toJson(buildResponse(gaugeConfig));
    }

    @POST("/backend/config/gauges/update")
    String updateGauge(String content) throws IOException {
        GaugeConfigDto gaugeConfigDto = Marshaling.fromJson(content, GaugeConfigDto.class);
        GaugeConfig gaugeConfig = gaugeConfigDto.toConfig();
        String version = gaugeConfigDto.version();
        checkNotNull(version, "Missing required request property: version");
        configService.updateGaugeConfig(gaugeConfig, version);
        return Marshaling2.toJson(buildResponse(gaugeConfig));
    }

    @POST("/backend/config/gauges/remove")
    void removeGauge(String content) throws IOException {
        String version = mapper.readValue(content, String.class);
        checkNotNull(version);
        configService.deleteGaugeConfig(version);
    }

    private GaugeResponse buildResponse(GaugeConfig gaugeConfig) {
        MBeanInfo mbeanInfo = null;
        try {
            mbeanInfo = getMBeanInfo(gaugeConfig.mbeanObjectName());
        } catch (Exception e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
        }
        ImmutableGaugeResponse.Builder builder = ImmutableGaugeResponse.builder()
                .config(GaugeConfigDto.fromConfig(gaugeConfig));
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
            } else if (attributeType.equals(CompositeData.class.getName())) {
                Descriptor descriptor = attribute.getDescriptor();
                Object descriptorFieldValue = descriptor.getFieldValue("openType");
                if (descriptorFieldValue instanceof CompositeType) {
                    CompositeType compositeType = (CompositeType) descriptorFieldValue;
                    attributeNames.addAll(getCompositeTypeAttributeNames(attribute, compositeType));
                }
            }
        }
        return attributeNames;
    }

    private static List<String> getCompositeTypeAttributeNames(MBeanAttributeInfo attribute,
            CompositeType compositeType) {
        List<String> attributeNames = Lists.newArrayList();
        for (String itemName : compositeType.keySet()) {
            OpenType<?> itemType = compositeType.getType(itemName);
            if (itemType == null) {
                continue;
            }
            String className = itemType.getClassName();
            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                logger.warn(e.getMessage(), e);
                continue;
            }
            if (Number.class.isAssignableFrom(clazz)) {
                attributeNames.add(attribute.getName() + "." + itemName);
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
    abstract static class GaugeConfigWithWarningMessages {
        abstract GaugeConfigDto config();
        abstract List<String> warningMessages();
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
        abstract @Nullable String gaugeVersion();
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
    abstract static class GaugeResponse {
        abstract GaugeConfigDto config();
        @Value.Default
        boolean mbeanUnavailable() {
            return false;
        }
        abstract List<String> mbeanAvailableAttributeNames();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class GaugeConfigDto {

        abstract String name();
        abstract String mbeanObjectName();
        abstract List<String> mbeanAttributeNames();
        abstract @Nullable String version(); // null for insert operations

        private static GaugeConfigDto fromConfig(GaugeConfig gaugeConfig) {
            return ImmutableGaugeConfigDto.builder()
                    .name(gaugeConfig.name())
                    .mbeanObjectName(gaugeConfig.mbeanObjectName())
                    .addAllMbeanAttributeNames(gaugeConfig.mbeanAttributeNames())
                    .version(gaugeConfig.version())
                    .build();
        }

        private GaugeConfig toConfig() {
            return ImmutableGaugeConfig.builder()
                    .name(name())
                    .mbeanObjectName(mbeanObjectName())
                    .addAllMbeanAttributeNames(mbeanAttributeNames())
                    .build();
        }
    }
}
