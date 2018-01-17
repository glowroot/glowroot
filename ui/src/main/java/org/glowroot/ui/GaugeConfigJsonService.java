/*
 * Copyright 2014-2018 the original author or authors.
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
import com.google.common.base.Joiner;
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
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.DuplicateMBeanObjectNameException;
import org.glowroot.common.repo.util.Gauges;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Styles;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.MBeanAttribute;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class GaugeConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(GaugeConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<GaugeConfig> orderingByName = new Ordering<GaugeConfig>() {
        @Override
        public int compare(GaugeConfig left, GaugeConfig right) {
            Joiner joiner = Joiner.on('/');
            return joiner.join(Gauges.getDisplayParts(left.getMbeanObjectName()))
                    .compareToIgnoreCase(
                            joiner.join(Gauges.getDisplayParts(right.getMbeanObjectName())));
        }
    };

    private final ConfigRepository configRepository;
    private final @Nullable LiveJvmService liveJvmService;

    GaugeConfigJsonService(ConfigRepository configRepository,
            @Nullable LiveJvmService liveJvmService) {
        this.configRepository = configRepository;
        this.liveJvmService = liveJvmService;
    }

    @GET(path = "/backend/config/gauges", permission = "agent:config:view:gauge")
    String getGaugeConfig(@BindAgentId String agentId, @BindRequest GaugeConfigRequest request)
            throws Exception {
        Optional<String> version = request.version();
        if (version.isPresent()) {
            GaugeConfig gaugeConfig = configRepository.getGaugeConfig(agentId, version.get());
            if (gaugeConfig == null) {
                throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
            }
            return getGaugeResponse(agentId, gaugeConfig);
        } else {
            List<GaugeConfigWithWarningMessages> responses = Lists.newArrayList();
            List<GaugeConfig> gaugeConfigs = configRepository.getGaugeConfigs(agentId);
            gaugeConfigs = orderingByName.immutableSortedCopy(gaugeConfigs);
            for (GaugeConfig gaugeConfig : gaugeConfigs) {
                responses.add(ImmutableGaugeConfigWithWarningMessages.builder()
                        .config(GaugeConfigDto.create(gaugeConfig))
                        .build());
            }
            return mapper.writeValueAsString(responses);
        }
    }

    @GET(path = "/backend/config/new-gauge-check-agent-connected",
            permission = "agent:config:edit:gauge")
    String checkAgentConnected(@BindAgentId String agentId) throws Exception {
        checkNotNull(liveJvmService); // agent:config:edit is disabled in offline viewer
        return Boolean.toString(liveJvmService.isAvailable(agentId));
    }

    @GET(path = "/backend/config/matching-mbean-objects", permission = "agent:config:edit:gauge")
    String getMatchingMBeanObjects(@BindAgentId String agentId,
            @BindRequest MBeanObjectNameRequest request) throws Exception {
        checkNotNull(liveJvmService); // agent:config:edit is disabled in offline viewer
        try {
            return mapper.writeValueAsString(liveJvmService.getMatchingMBeanObjectNames(agentId,
                    request.partialObjectName(), request.limit()));
        } catch (AgentNotConnectedException e) {
            logger.debug(e.getMessage(), e);
            return "[]";
        }
    }

    @GET(path = "/backend/config/mbean-attributes", permission = "agent:config:edit:gauge")
    String getMBeanAttributes(@BindAgentId String agentId,
            @BindRequest MBeanAttributeNamesRequest request) throws Exception {
        checkNotNull(liveJvmService); // agent:config:edit is disabled in offline viewer
        boolean duplicateMBean = false;
        for (GaugeConfig gaugeConfig : configRepository.getGaugeConfigs(agentId)) {
            if (gaugeConfig.getMbeanObjectName().equals(request.objectName())
                    && !Versions.getVersion(gaugeConfig).equals(request.gaugeVersion())) {
                duplicateMBean = true;
                break;
            }
        }
        MBeanMeta mbeanMeta = liveJvmService.getMBeanMeta(agentId, request.objectName());
        return mapper.writeValueAsString(ImmutableMBeanAttributeNamesResponse.builder()
                .duplicateMBean(duplicateMBean)
                .mbeanUnmatched(mbeanMeta.getUnmatched())
                .mbeanUnavailable(mbeanMeta.getUnavailable())
                .addAllMbeanAttributes(mbeanMeta.getAttributeNameList())
                .build());
    }

    @POST(path = "/backend/config/gauges/add", permission = "agent:config:edit:gauge")
    String addGauge(@BindAgentId String agentId, @BindRequest GaugeConfigDto gaugeConfigDto)
            throws Exception {
        GaugeConfig gaugeConfig = gaugeConfigDto.convert();
        try {
            configRepository.insertGaugeConfig(agentId, gaugeConfig);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return getGaugeResponse(agentId, gaugeConfig);
    }

    @POST(path = "/backend/config/gauges/update", permission = "agent:config:edit:gauge")
    String updateGauge(@BindAgentId String agentId, @BindRequest GaugeConfigDto gaugeConfigDto)
            throws Exception {
        GaugeConfig gaugeConfig = gaugeConfigDto.convert();
        String version = gaugeConfigDto.version().get();
        try {
            configRepository.updateGaugeConfig(agentId, gaugeConfig, version);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return getGaugeResponse(agentId, gaugeConfig);
    }

    @POST(path = "/backend/config/gauges/remove", permission = "agent:config:edit:gauge")
    void removeGauge(@BindAgentId String agentId, @BindRequest GaugeConfigRequest request)
            throws Exception {
        configRepository.deleteGaugeConfig(agentId, request.version().get());
    }

    private String getGaugeResponse(String agentId, GaugeConfig gaugeConfig) throws Exception {
        ImmutableGaugeResponse.Builder builder = ImmutableGaugeResponse.builder()
                .config(GaugeConfigDto.create(gaugeConfig));
        MBeanMeta mbeanMeta = null;
        if (liveJvmService != null) {
            try {
                mbeanMeta = liveJvmService.getMBeanMeta(agentId, gaugeConfig.getMbeanObjectName());
            } catch (AgentNotConnectedException e) {
                logger.debug(e.getMessage(), e);
            }
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
        return mapper.writeValueAsString(builder.build());
    }

    @Value.Immutable
    interface GaugeConfigWithWarningMessages {
        GaugeConfigDto config();
        ImmutableList<String> warningMessages();
    }

    @Value.Immutable
    interface GaugeConfigRequest {
        Optional<String> version();
    }

    @Value.Immutable
    interface MBeanObjectNameRequest {
        String partialObjectName();
        int limit();
    }

    @Value.Immutable
    interface MBeanAttributeNamesRequest {
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

        abstract @Nullable String display(); // only used in response
        abstract List<String> displayPath(); // only used in response
        abstract String mbeanObjectName();
        abstract ImmutableList<ImmutableMBeanAttributeDto> mbeanAttributes();
        abstract Optional<String> version(); // absent for insert operations

        private GaugeConfig convert() {
            GaugeConfig.Builder builder = GaugeConfig.newBuilder()
                    .setMbeanObjectName(mbeanObjectName());
            for (MBeanAttributeDto mbeanAttribute : mbeanAttributes()) {
                builder.addMbeanAttribute(mbeanAttribute.convert());
            }
            return builder.build();
        }

        private static GaugeConfigDto create(GaugeConfig config) {
            List<String> displayPath = Gauges.getDisplayParts(config.getMbeanObjectName());
            String display = Joiner.on(Gauges.DISPLAY_PARTS_SEPARATOR).join(displayPath);
            ImmutableGaugeConfigDto.Builder builder = ImmutableGaugeConfigDto.builder()
                    .display(display)
                    .displayPath(displayPath)
                    .mbeanObjectName(config.getMbeanObjectName());
            for (MBeanAttribute mbeanAttribute : config.getMbeanAttributeList()) {
                builder.addMbeanAttributes(MBeanAttributeDto.create(mbeanAttribute));
            }
            return builder.version(Versions.getVersion(config))
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

        private static ImmutableMBeanAttributeDto create(MBeanAttribute mbeanAttribute) {
            return ImmutableMBeanAttributeDto.builder()
                    .name(mbeanAttribute.getName())
                    .counter(mbeanAttribute.getCounter())
                    .build();
        }
    }
}
