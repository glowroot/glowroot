/*
 * Copyright 2014-2016 the original author or authors.
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

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Styles;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.DuplicateMBeanObjectNameException;
import org.glowroot.storage.repo.helper.Gauges;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.MBeanAttribute;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;

import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class GaugeConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(GaugeConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<GaugeConfig> orderingByName = new Ordering<GaugeConfig>() {
        @Override
        public int compare(GaugeConfig left, GaugeConfig right) {
            return Gauges.display(left.getMbeanObjectName())
                    .compareToIgnoreCase(Gauges.display(right.getMbeanObjectName()));
        }
    };

    static {
        SimpleModule module = new SimpleModule();
        module.addAbstractTypeMapping(MBeanAttributeDto.class, ImmutableMBeanAttributeDto.class);
        mapper.registerModule(module);
    }

    private final ConfigRepository configRepository;
    private final LiveJvmService liveJvmService;

    GaugeConfigJsonService(ConfigRepository configRepository, LiveJvmService liveJvmService) {
        this.configRepository = configRepository;
        this.liveJvmService = liveJvmService;
    }

    @GET("/backend/config/gauges")
    String getGaugeConfig(String queryString) throws Exception {
        GaugeConfigRequest request = QueryStrings.decode(queryString, GaugeConfigRequest.class);
        String serverId = request.serverId();
        Optional<String> version = request.version();
        if (version.isPresent()) {
            GaugeConfig gaugeConfig =
                    configRepository.getGaugeConfig(serverId, version.get());
            if (gaugeConfig == null) {
                throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
            }
            return mapper.writeValueAsString(buildResponse(serverId, gaugeConfig));
        } else {
            List<GaugeConfigWithWarningMessages> responses = Lists.newArrayList();
            List<GaugeConfig> gaugeConfigs = configRepository.getGaugeConfigs(serverId);
            gaugeConfigs = orderingByName.immutableSortedCopy(gaugeConfigs);
            for (GaugeConfig gaugeConfig : gaugeConfigs) {
                responses.add(ImmutableGaugeConfigWithWarningMessages.builder()
                        .config(GaugeConfigDto.create(gaugeConfig))
                        .build());
            }
            return mapper.writeValueAsString(responses);
        }
    }

    @GET("/backend/config/matching-mbean-objects")
    String getMatchingMBeanObjects(String queryString) throws Exception {
        MBeanObjectNameRequest request =
                QueryStrings.decode(queryString, MBeanObjectNameRequest.class);
        try {
            return mapper.writeValueAsString(liveJvmService.getMatchingMBeanObjectNames(
                    request.serverId(), request.partialObjectName(), request.limit()));
        } catch (AgentNotConnectedException e) {
            return "[]";
        }
    }

    @GET("/backend/config/mbean-attributes")
    String getMBeanAttributes(String queryString) throws Exception {
        MBeanAttributeNamesRequest request =
                QueryStrings.decode(queryString, MBeanAttributeNamesRequest.class);
        String serverId = request.serverId();
        boolean duplicateMBean = false;
        for (GaugeConfig gaugeConfig : configRepository.getGaugeConfigs(serverId)) {
            if (gaugeConfig.getMbeanObjectName().equals(request.objectName())
                    && !Versions.getVersion(gaugeConfig).equals(request.gaugeVersion())) {
                duplicateMBean = true;
                break;
            }
        }
        MBeanMeta mbeanMeta = liveJvmService.getMBeanMeta(serverId, request.objectName());
        return mapper.writeValueAsString(ImmutableMBeanAttributeNamesResponse.builder()
                .duplicateMBean(duplicateMBean)
                .mbeanUnmatched(mbeanMeta.getUnmatched())
                .mbeanUnavailable(mbeanMeta.getUnavailable())
                .addAllMbeanAttributes(mbeanMeta.getAttributeNameList())
                .build());
    }

    @POST("/backend/config/gauges/add")
    String addGauge(String content) throws Exception {
        GaugeConfigDto gaugeConfigDto = mapper.readValue(content, ImmutableGaugeConfigDto.class);
        String serverId = gaugeConfigDto.serverId().get();
        GaugeConfig gaugeConfig = gaugeConfigDto.convert();
        try {
            configRepository.insertGaugeConfig(serverId, gaugeConfig);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return mapper.writeValueAsString(buildResponse(serverId, gaugeConfig));
    }

    @POST("/backend/config/gauges/update")
    String updateGauge(String content) throws Exception {
        GaugeConfigDto gaugeConfigDto = mapper.readValue(content, ImmutableGaugeConfigDto.class);
        String serverId = gaugeConfigDto.serverId().get();
        GaugeConfig gaugeConfig = gaugeConfigDto.convert();
        String version = gaugeConfigDto.version().get();
        try {
            configRepository.updateGaugeConfig(serverId, gaugeConfig, version);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return mapper.writeValueAsString(buildResponse(serverId, gaugeConfig));
    }

    @POST("/backend/config/gauges/remove")
    void removeGauge(String content) throws Exception {
        GaugeConfigRequest request = mapper.readValue(content, ImmutableGaugeConfigRequest.class);
        configRepository.deleteGaugeConfig(request.serverId(), request.version().get());
    }

    private GaugeResponse buildResponse(String serverId, GaugeConfig gaugeConfig) throws Exception {
        ImmutableGaugeResponse.Builder builder = ImmutableGaugeResponse.builder()
                .config(GaugeConfigDto.create(gaugeConfig));
        MBeanMeta mbeanMeta;
        try {
            mbeanMeta = liveJvmService.getMBeanMeta(serverId, gaugeConfig.getMbeanObjectName());
        } catch (AgentNotConnectedException e) {
            logger.debug(e.getMessage(), e);
            mbeanMeta = null;
        }
        builder.agentNotConnected(mbeanMeta == null)
                .mbeanUnmatched(mbeanMeta != null && mbeanMeta.getUnmatched())
                .mbeanUnavailable(mbeanMeta != null && mbeanMeta.getUnavailable());
        if (mbeanMeta == null) {
            // agent not connected
            for (MBeanAttribute mbeanAttribute : gaugeConfig.getMbeanAttributeList()) {
                builder.addMbeanAvailableAttributeNames(mbeanAttribute.getName());
            }
        } else {
            builder.addAllMbeanAvailableAttributeNames(mbeanMeta.getAttributeNameList());
        }
        return builder.build();
    }

    @Value.Immutable
    interface GaugeConfigWithWarningMessages {
        GaugeConfigDto config();
        ImmutableList<String> warningMessages();
    }

    @Value.Immutable
    interface GaugeConfigRequest {
        String serverId();
        Optional<String> version();
    }

    @Value.Immutable
    interface MBeanObjectNameRequest {
        String serverId();
        String partialObjectName();
        int limit();
    }

    @Value.Immutable
    interface MBeanAttributeNamesRequest {
        String serverId();
        String objectName();
        @Nullable
        String gaugeVersion();
    }

    @Value.Immutable
    interface MBeanAttributeNamesResponse {
        boolean mbeanUnavailable();
        boolean mbeanUnmatched();
        boolean duplicateMBean();
        ImmutableList<String> mbeanAttributes();
    }

    @Value.Immutable
    interface GaugeResponse {
        GaugeConfigDto config();
        boolean agentNotConnected();
        boolean mbeanUnavailable();
        boolean mbeanUnmatched();
        ImmutableList<String> mbeanAvailableAttributeNames();
    }

    @Value.Immutable
    abstract static class GaugeConfigDto {

        abstract Optional<String> serverId(); // only used in request
        abstract @Nullable String display(); // only used in response
        abstract String mbeanObjectName();
        abstract ImmutableList<MBeanAttributeDto> mbeanAttributes();
        abstract Optional<String> version(); // absent for insert operations

        private GaugeConfig convert() {
            AgentConfig.GaugeConfig.Builder builder = GaugeConfig.newBuilder()
                    .setMbeanObjectName(mbeanObjectName());
            for (MBeanAttributeDto mbeanAttribute : mbeanAttributes()) {
                builder.addMbeanAttribute(mbeanAttribute.convert());
            }
            return builder.build();
        }

        private static GaugeConfigDto create(GaugeConfig gaugeConfig) {
            ImmutableGaugeConfigDto.Builder builder = ImmutableGaugeConfigDto.builder()
                    .display(Gauges.display(gaugeConfig.getMbeanObjectName()))
                    .mbeanObjectName(gaugeConfig.getMbeanObjectName());
            for (MBeanAttribute mbeanAttribute : gaugeConfig.getMbeanAttributeList()) {
                builder.addMbeanAttributes(MBeanAttributeDto.create(mbeanAttribute));
            }
            return builder.version(Versions.getVersion(gaugeConfig))
                    .build();
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    abstract static class MBeanAttributeDto {

        abstract String name();
        abstract boolean counter();

        private MBeanAttribute convert() {
            return MBeanAttribute.newBuilder()
                    .setName(name())
                    .setCounter(counter())
                    .build();
        }

        private static MBeanAttributeDto create(MBeanAttribute mbeanAttribute) {
            return ImmutableMBeanAttributeDto.builder()
                    .name(mbeanAttribute.getName())
                    .counter(mbeanAttribute.getCounter())
                    .build();
        }
    }
}
