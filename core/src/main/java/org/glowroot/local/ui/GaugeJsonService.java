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
import org.glowroot.config.Gauge;
import org.glowroot.config.ImmutableGauge;
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
    String getGaugeList() {
        List<GaugeWithWarningMessages> responses = Lists.newArrayList();
        List<Gauge> gauges = configService.getGauges();
        gauges = Gauge.orderingByName.immutableSortedCopy(gauges);
        for (Gauge gauge : gauges) {
            responses.add(ImmutableGaugeWithWarningMessages.builder()
                    .config(GaugeDto.fromConfig(gauge))
                    .build());
        }
        return Marshaling2.toJson(responses, GaugeWithWarningMessages.class);
    }

    @GET("/backend/config/gauges/([0-9a-f]{40})")
    String getGauge(String version) {
        Gauge gauge = configService.getGauge(version);
        if (gauge == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        return Marshaling2.toJson(buildResponse(gauge));
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
        for (Gauge gauge : configService.getGauges()) {
            if (gauge.mbeanObjectName().equals(request.mbeanObjectName())
                    && !gauge.version().equals(request.gaugeVersion())) {
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
        GaugeDto gaugeDto = Marshaling.fromJson(content, GaugeDto.class);
        Gauge gauge = gaugeDto.toConfig();
        try {
            configService.insertGauge(gauge);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return Marshaling2.toJson(buildResponse(gauge));
    }

    @POST("/backend/config/gauges/update")
    String updateGauge(String content) throws IOException {
        GaugeDto gaugeDto = Marshaling.fromJson(content, GaugeDto.class);
        Gauge gauge = gaugeDto.toConfig();
        String version = gaugeDto.version();
        checkNotNull(version, "Missing required request property: version");
        configService.updateGauge(gauge, version);
        return Marshaling2.toJson(buildResponse(gauge));
    }

    @POST("/backend/config/gauges/remove")
    void removeGauge(String content) throws IOException {
        String version = mapper.readValue(content, String.class);
        checkNotNull(version);
        configService.deleteGauge(version);
    }

    private GaugeResponse buildResponse(Gauge gauge) {
        MBeanInfo mbeanInfo = null;
        try {
            mbeanInfo = getMBeanInfo(gauge.mbeanObjectName());
        } catch (Exception e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
        }
        ImmutableGaugeResponse.Builder builder = ImmutableGaugeResponse.builder()
                .config(GaugeDto.fromConfig(gauge));
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
    abstract static class GaugeWithWarningMessages {
        abstract GaugeDto config();
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
        abstract GaugeDto config();
        @Value.Default
        boolean mbeanUnavailable() {
            return false;
        }
        abstract List<String> mbeanAvailableAttributeNames();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class GaugeDto {

        abstract String name();
        abstract String mbeanObjectName();
        abstract List<String> mbeanAttributeNames();
        abstract @Nullable String version(); // null for insert operations

        private static GaugeDto fromConfig(Gauge gauge) {
            return ImmutableGaugeDto.builder()
                    .name(gauge.name())
                    .mbeanObjectName(gauge.mbeanObjectName())
                    .addAllMbeanAttributeNames(gauge.mbeanAttributeNames())
                    .version(gauge.version())
                    .build();
        }

        private Gauge toConfig() {
            return ImmutableGauge.builder()
                    .name(name())
                    .mbeanObjectName(mbeanObjectName())
                    .addAllMbeanAttributeNames(mbeanAttributeNames())
                    .build();
        }
    }
}
