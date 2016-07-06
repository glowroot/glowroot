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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.DuplicateMBeanObjectNameException;
import org.glowroot.storage.repo.GaugeValueRepository.Gauge;
import org.glowroot.storage.repo.helper.Gauges;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.Proto.OptionalDouble;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class AlertConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AlertConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    @VisibleForTesting
    static final Ordering<AlertConfig> orderingByName = new Ordering<AlertConfig>() {
        @Override
        public int compare(AlertConfig left, AlertConfig right) {
            if (left.getKind() == AlertKind.TRANSACTION
                    && right.getKind() == AlertKind.TRANSACTION) {
                return left.getTransactionType().compareToIgnoreCase(right.getTransactionType());
            }
            if (left.getKind() == AlertKind.GAUGE && right.getKind() == AlertKind.GAUGE) {
                return left.getGaugeName().compareToIgnoreCase(right.getGaugeName());
            }
            if (left.getKind() == AlertKind.TRANSACTION && right.getKind() == AlertKind.GAUGE) {
                return -1;
            }
            // left is gauge, right is transaction
            return 1;
        }
    };

    private final ConfigRepository configRepository;

    AlertConfigJsonService(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @GET(path = "/backend/config/alerts", permission = "agent:config:view:alert")
    String getAlert(@BindAgentId String agentId, @BindRequest AlertConfigRequest request)
            throws Exception {
        Optional<String> version = request.version();
        if (version.isPresent()) {
            AlertConfig alertConfig = configRepository.getAlertConfig(agentId, version.get());
            if (alertConfig == null) {
                throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
            }
            return mapper.writeValueAsString(AlertConfigDto.create(alertConfig));
        } else {
            List<AlertConfigDto> alertConfigDtos = Lists.newArrayList();
            List<AlertConfig> alertConfigs = configRepository.getAlertConfigs(agentId);
            alertConfigs = orderingByName.immutableSortedCopy(alertConfigs);
            for (AlertConfig alertConfig : alertConfigs) {
                alertConfigDtos.add(AlertConfigDto.create(alertConfig));
            }
            return mapper.writeValueAsString(alertConfigDtos);
        }
    }

    @POST(path = "/backend/config/alerts/add", permission = "agent:config:edit:alert")
    String addAlert(@BindAgentId String agentId, @BindRequest AlertConfigDto configDto)
            throws Exception {
        AlertConfig alertConfig = configDto.convert();
        try {
            configRepository.insertAlertConfig(agentId, alertConfig);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return mapper.writeValueAsString(AlertConfigDto.create(alertConfig));
    }

    @POST(path = "/backend/config/alerts/update", permission = "agent:config:edit:alert")
    String updateAlert(@BindAgentId String agentId, @BindRequest AlertConfigDto configDto)
            throws Exception {
        AlertConfig alertConfig = configDto.convert();
        configRepository.updateAlertConfig(agentId, alertConfig, configDto.version().get());
        return mapper.writeValueAsString(AlertConfigDto.create(alertConfig));
    }

    @POST(path = "/backend/config/alerts/remove", permission = "agent:config:edit:alert")
    void removeAlert(@BindAgentId String agentId, @BindRequest AlertConfigRequest request)
            throws Exception {
        configRepository.deleteAlertConfig(agentId, request.version().get());
    }

    @Value.Immutable
    interface AlertConfigRequest {
        Optional<String> version();
    }

    @Value.Immutable
    abstract static class AlertConfigDto {

        abstract AlertKind kind();

        abstract @Nullable String transactionType();
        abstract @Nullable Double transactionPercentile();
        abstract @Nullable Integer transactionThresholdMillis();
        abstract @Nullable Integer minTransactionCount();
        abstract @Nullable String gaugeName();
        abstract @Nullable Double gaugeThreshold();
        abstract @Nullable String gaugeDisplay(); // only used in response
        abstract List<String> gaugeDisplayPath(); // only used in response
        abstract @Nullable String gaugeUnit(); // only used in response
        abstract @Nullable String gaugeGrouping(); // only used in response
        abstract int timePeriodSeconds();
        abstract ImmutableList<String> emailAddresses();
        abstract Optional<String> version(); // absent for insert operations

        private AlertConfig convert() {
            AlertConfig.Builder builder = AlertConfig.newBuilder()
                    .setKind(kind());
            String transactionType = transactionType();
            if (transactionType != null) {
                builder.setTransactionType(transactionType);
            }
            Double transactionPercentile = transactionPercentile();
            if (transactionPercentile != null) {
                builder.setTransactionPercentile(OptionalDouble.newBuilder()
                        .setValue(transactionPercentile));
            }
            Integer transactionThresholdMillis = transactionThresholdMillis();
            if (transactionThresholdMillis != null) {
                builder.setTransactionThresholdMillis(OptionalInt32.newBuilder()
                        .setValue(transactionThresholdMillis));
            }
            Integer minTransactionCount = minTransactionCount();
            if (minTransactionCount != null) {
                builder.setMinTransactionCount(OptionalInt32.newBuilder()
                        .setValue(minTransactionCount));
            }
            String gaugeName = gaugeName();
            if (gaugeName != null) {
                builder.setGaugeName(gaugeName);
            }
            Double gaugeThreshold = gaugeThreshold();
            if (gaugeThreshold != null) {
                builder.setGaugeThreshold(OptionalDouble.newBuilder()
                        .setValue(gaugeThreshold));
            }
            return builder.setTimePeriodSeconds(timePeriodSeconds())
                    .addAllEmailAddress(emailAddresses())
                    .build();
        }

        private static AlertConfigDto create(AlertConfig alertConfig) {
            Gauge gauge = null;
            if (alertConfig.getKind() == AlertKind.GAUGE) {
                gauge = Gauges.getGauge(alertConfig.getGaugeName());
            }
            ImmutableAlertConfigDto.Builder builder = ImmutableAlertConfigDto.builder()
                    .kind(alertConfig.getKind());
            String transactionType = alertConfig.getTransactionType();
            if (!transactionType.isEmpty()) {
                builder.transactionType(transactionType);
            }
            if (alertConfig.hasTransactionPercentile()) {
                builder.transactionPercentile(alertConfig.getTransactionPercentile().getValue());
            }
            if (alertConfig.hasTransactionThresholdMillis()) {
                builder.transactionThresholdMillis(
                        alertConfig.getTransactionThresholdMillis().getValue());
            }
            if (alertConfig.hasMinTransactionCount()) {
                builder.minTransactionCount(alertConfig.getMinTransactionCount().getValue());
            }
            String gaugeName = alertConfig.getGaugeName();
            if (!gaugeName.isEmpty()) {
                builder.gaugeName(gaugeName);
            }
            if (alertConfig.hasGaugeThreshold()) {
                builder.gaugeThreshold(alertConfig.getGaugeThreshold().getValue());
            }
            if (gauge != null) {
                builder.gaugeDisplay(gauge.display())
                        .gaugeDisplayPath(gauge.displayPath());
            }
            return builder.gaugeUnit(gauge == null ? "" : gauge.unit())
                    .gaugeGrouping(gauge == null ? "" : gauge.grouping())
                    .timePeriodSeconds(alertConfig.getTimePeriodSeconds())
                    .addAllEmailAddresses(alertConfig.getEmailAddressList())
                    .version(Versions.getVersion(alertConfig))
                    .build();
        }
    }
}
