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
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.PatternObjectNameQueryExp;
import org.glowroot.common.ObjectMappers;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ConfigService.DuplicateMBeanObjectNameException;
import org.glowroot.config.GaugeConfig;
import org.glowroot.config.GaugeConfigBase;
import org.glowroot.config.MBeanAttribute;
import org.glowroot.jvm.LazyPlatformMBeanServer;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class GaugeJsonService {

    private static final Logger logger = LoggerFactory.getLogger(GaugeJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ConfigService configService;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

    GaugeJsonService(ConfigService configService,
            LazyPlatformMBeanServer lazyPlatformMBeanServer) {
        this.configService = configService;
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
    }

    @GET("/backend/config/gauges")
    String getGaugeConfigs() throws JsonProcessingException {
        List<GaugeConfigWithWarningMessages> responses = Lists.newArrayList();
        List<GaugeConfig> gaugeConfigs = configService.getGaugeConfigs();
        gaugeConfigs = GaugeConfig.orderingByName.immutableSortedCopy(gaugeConfigs);
        for (GaugeConfig gaugeConfig : gaugeConfigs) {
            responses.add(GaugeConfigWithWarningMessages.builder()
                    .config(GaugeConfigDtoBase.fromConfig(gaugeConfig))
                    .build());
        }
        return mapper.writeValueAsString(responses);
    }

    @GET("/backend/config/gauges/([0-9a-f]{40})")
    String getGaugeConfig(String version) throws Exception {
        GaugeConfig gaugeConfig = configService.getGaugeConfig(version);
        if (gaugeConfig == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        return mapper.writeValueAsString(buildResponse(gaugeConfig));
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
        MBeanAttributeNamesResponse.Builder builder = MBeanAttributeNamesResponse.builder();
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            if (gaugeConfig.mbeanObjectName().equals(request.mbeanObjectName())
                    && !gaugeConfig.version().equals(request.gaugeVersion())) {
                builder.duplicateMBean(true);
                break;
            }
        }
        boolean pattern = request.mbeanObjectName().contains("*");
        List<MBeanInfo> mbeanInfos = getMBeanInfos(request.mbeanObjectName());
        if (mbeanInfos.isEmpty() && pattern) {
            builder.mbeanUnmatched(true);
        } else if (mbeanInfos.isEmpty()) {
            builder.mbeanUnavailable(true);
        } else {
            builder.addAllMbeanAttributes(getAttributeNames(mbeanInfos));
        }
        return mapper.writeValueAsString(builder.build());
    }

    @POST("/backend/config/gauges/add")
    String addGauge(String content) throws Exception {
        GaugeConfigDto gaugeConfigDto = mapper.readValue(content, GaugeConfigDto.class);
        GaugeConfig gaugeConfig = gaugeConfigDto.toConfig();
        try {
            configService.insertGaugeConfig(gaugeConfig);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return mapper.writeValueAsString(buildResponse(gaugeConfig));
    }

    @POST("/backend/config/gauges/update")
    String updateGauge(String content) throws Exception {
        GaugeConfigDto gaugeConfigDto = mapper.readValue(content, GaugeConfigDto.class);
        GaugeConfig gaugeConfig = gaugeConfigDto.toConfig();
        String version = gaugeConfigDto.version();
        checkNotNull(version, "Missing required request property: version");
        try {
            configService.updateGaugeConfig(gaugeConfig, version);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return mapper.writeValueAsString(buildResponse(gaugeConfig));
    }

    @POST("/backend/config/gauges/remove")
    void removeGauge(String content) throws IOException {
        String version = mapper.readValue(content, String.class);
        checkNotNull(version);
        configService.deleteGaugeConfig(version);
    }

    private GaugeResponse buildResponse(GaugeConfig gaugeConfig) throws InterruptedException {
        boolean pattern = gaugeConfig.mbeanObjectName().contains("*");
        List<MBeanInfo> mbeanInfos = getMBeanInfos(gaugeConfig.mbeanObjectName());
        GaugeResponse.Builder builder =
                GaugeResponse.builder().config(GaugeConfigDtoBase.fromConfig(gaugeConfig));
        if (mbeanInfos.isEmpty() && pattern) {
            builder.mbeanUnmatched(true);
        } else if (mbeanInfos.isEmpty()) {
            builder.mbeanUnavailable(true);
        } else {
            builder.addAllMbeanAvailableAttributeNames(getAttributeNames(mbeanInfos));
        }
        return builder.build();
    }

    private List<MBeanInfo> getMBeanInfos(String objectName) throws InterruptedException {
        if (!objectName.contains("*")) {
            try {
                return ImmutableList.of(lazyPlatformMBeanServer.getMBeanInfo(
                        ObjectName.getInstance(objectName)));
            } catch (Exception e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
                return ImmutableList.of();
            }
        }
        Set<ObjectInstance> objectInstances = lazyPlatformMBeanServer.queryMBeans(null,
                new PatternObjectNameQueryExp(objectName));
        List<MBeanInfo> mbeanInfos = Lists.newArrayList();
        for (ObjectInstance objectInstance : objectInstances) {
            try {
                mbeanInfos.add(lazyPlatformMBeanServer.getMBeanInfo(
                        objectInstance.getObjectName()));
            } catch (Exception e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
            }
        }
        return mbeanInfos;
    }

    private static Set<String> getAttributeNames(List<MBeanInfo> mbeanInfos) {
        Set<String> attributeNames = Sets.newHashSet();
        for (MBeanInfo mbeanInfo : mbeanInfos) {
            for (MBeanAttributeInfo attribute : mbeanInfo.getAttributes()) {
                if (attribute.isReadable()) {
                    addNumericAttributes(attribute, attributeNames);
                }
            }
        }
        return attributeNames;
    }

    private static void addNumericAttributes(MBeanAttributeInfo attribute,
            Set<String> attributeNames) {
        String attributeType = attribute.getType();
        if (attributeType.equals("long") || attributeType.equals("int")
                || attributeType.equals("double") || attributeType.equals("float")) {
            attributeNames.add(attribute.getName());
        } else if (attributeType.equals(CompositeData.class.getName())) {
            Descriptor descriptor = attribute.getDescriptor();
            Object descriptorFieldValue = descriptor.getFieldValue("openType");
            if (descriptorFieldValue instanceof CompositeType) {
                CompositeType compositeType = (CompositeType) descriptorFieldValue;
                attributeNames.addAll(getCompositeTypeAttributeNames(attribute,
                        compositeType));
            }
        }
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
                attributeNames.add(attribute.getName() + '/' + itemName);
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
    @JsonSerialize
    abstract static class GaugeConfigWithWarningMessagesBase {
        abstract GaugeConfigDto config();
        abstract ImmutableList<String> warningMessages();
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class MBeanObjectNameRequestBase {
        abstract String partialMBeanObjectName();
        abstract int limit();
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class MBeanAttributeNamesRequestBase {
        abstract String mbeanObjectName();
        abstract @Nullable String gaugeVersion();
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class MBeanAttributeNamesResponseBase {
        @Value.Default
        boolean mbeanUnavailable() {
            return false;
        }
        @Value.Default
        boolean mbeanUnmatched() {
            return false;
        }
        @Value.Default
        boolean duplicateMBean() {
            return false;
        }
        abstract ImmutableList<String> mbeanAttributes();
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class GaugeResponseBase {
        abstract GaugeConfigDto config();
        @Value.Default
        boolean mbeanUnavailable() {
            return false;
        }
        @Value.Default
        boolean mbeanUnmatched() {
            return false;
        }
        abstract ImmutableList<String> mbeanAvailableAttributeNames();
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class GaugeConfigDtoBase {

        // name is only used in one direction since it is a derived attribute
        abstract @Nullable String display();
        abstract String mbeanObjectName();
        abstract ImmutableList<MBeanAttribute> mbeanAttributes();
        abstract @Nullable String version(); // null for insert operations

        private static GaugeConfigDto fromConfig(GaugeConfig gaugeConfig) {
            return GaugeConfigDto.builder()
                    .display(GaugeConfigBase.display(gaugeConfig.mbeanObjectName()))
                    .mbeanObjectName(gaugeConfig.mbeanObjectName())
                    .addAllMbeanAttributes(gaugeConfig.mbeanAttributes())
                    .version(gaugeConfig.version())
                    .build();
        }
        GaugeConfig toConfig() {
            return GaugeConfig.builder()
                    .mbeanObjectName(mbeanObjectName())
                    .addAllMbeanAttributes(mbeanAttributes())
                    .build();
        }
    }
}
