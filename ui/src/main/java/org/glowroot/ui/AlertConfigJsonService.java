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
import org.glowroot.storage.config.AlertConfig;
import org.glowroot.storage.config.AlertConfig.AlertKind;
import org.glowroot.storage.config.ImmutableAlertConfig;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.DuplicateMBeanObjectNameException;
import org.glowroot.storage.repo.helper.Gauges;

import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class AlertConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AlertConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    @VisibleForTesting
    static final Ordering<AlertConfig> orderingByName = new Ordering<AlertConfig>() {
        @Override
        public int compare(AlertConfig left, AlertConfig right) {
            if (left.kind() == AlertKind.TRANSACTION && right.kind() == AlertKind.TRANSACTION) {
                return left.transactionType().compareToIgnoreCase(right.transactionType());
            }
            if (left.kind() == AlertKind.GAUGE && right.kind() == AlertKind.GAUGE) {
                return left.gaugeName().compareToIgnoreCase(right.gaugeName());
            }
            if (left.kind() == AlertKind.TRANSACTION && right.kind() == AlertKind.GAUGE) {
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

    @GET("/backend/config/alerts")
    String getAlert(String queryString) throws Exception {
        AlertConfigRequest request = QueryStrings.decode(queryString, AlertConfigRequest.class);
        Optional<String> version = request.version();
        if (version.isPresent()) {
            AlertConfig alertConfig =
                    configRepository.getAlertConfig(request.agentId(), version.get());
            if (alertConfig == null) {
                throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
            }
            return mapper.writeValueAsString(AlertConfigDto.create(alertConfig));
        } else {
            List<AlertConfigDto> alertConfigDtos = Lists.newArrayList();
            List<AlertConfig> alertConfigs = configRepository.getAlertConfigs(request.agentId());
            alertConfigs = orderingByName.immutableSortedCopy(alertConfigs);
            for (AlertConfig alertConfig : alertConfigs) {
                alertConfigDtos.add(AlertConfigDto.create(alertConfig));
            }
            return mapper.writeValueAsString(alertConfigDtos);
        }
    }

    @POST("/backend/config/alerts/add")
    String addAlert(String content) throws Exception {
        AlertConfigDto alertConfigDto = mapper.readValue(content, ImmutableAlertConfigDto.class);
        AlertConfig alertConfig = alertConfigDto.convert();
        try {
            configRepository.insertAlertConfig(alertConfigDto.agentId().get(), alertConfig);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return mapper.writeValueAsString(AlertConfigDto.create(alertConfig));
    }

    @POST("/backend/config/alerts/update")
    String updateAlert(String content) throws Exception {
        AlertConfigDto alertConfigDto = mapper.readValue(content, ImmutableAlertConfigDto.class);
        AlertConfig alertConfig = alertConfigDto.convert();
        configRepository.updateAlertConfig(alertConfigDto.agentId().get(), alertConfig,
                alertConfigDto.version().get());
        return mapper.writeValueAsString(AlertConfigDto.create(alertConfig));
    }

    @POST("/backend/config/alerts/remove")
    void removeAlert(String content) throws Exception {
        AlertConfigRequest request = mapper.readValue(content, ImmutableAlertConfigRequest.class);
        configRepository.deleteAlertConfig(request.agentId(), request.version().get());
    }

    @Value.Immutable
    interface AlertConfigRequest {
        String agentId();
        Optional<String> version();
    }

    @Value.Immutable
    abstract static class AlertConfigDto {

        abstract AlertKind kind();

        abstract Optional<String> agentId(); // only used in request
        abstract String transactionType();
        abstract @Nullable Double transactionPercentile();
        abstract @Nullable Integer transactionThresholdMillis();
        abstract @Nullable Integer minTransactionCount();
        abstract String gaugeName();
        abstract @Nullable String gaugeDisplay(); // only used in response
        abstract @Nullable Double gaugeThreshold();
        abstract int timePeriodSeconds();
        abstract ImmutableList<String> emailAddresses();
        abstract Optional<String> version(); // absent for insert operations

        private AlertConfig convert() {
            return ImmutableAlertConfig.builder()
                    .kind(kind())
                    .transactionType(transactionType())
                    .transactionPercentile(transactionPercentile())
                    .transactionThresholdMillis(transactionThresholdMillis())
                    .minTransactionCount(minTransactionCount())
                    .gaugeName(gaugeName())
                    .gaugeThreshold(gaugeThreshold())
                    .timePeriodSeconds(timePeriodSeconds())
                    .addAllEmailAddresses(emailAddresses())
                    .build();
        }

        private static AlertConfigDto create(AlertConfig alertConfig) {
            String gaugeDisplay = alertConfig.kind() == AlertKind.GAUGE
                    ? Gauges.getGauge(alertConfig.gaugeName()).display() : null;
            return ImmutableAlertConfigDto.builder()
                    .kind(alertConfig.kind())
                    .transactionType(alertConfig.transactionType())
                    .transactionPercentile(alertConfig.transactionPercentile())
                    .transactionThresholdMillis(alertConfig.transactionThresholdMillis())
                    .minTransactionCount(alertConfig.minTransactionCount())
                    .gaugeName(alertConfig.gaugeName())
                    .gaugeDisplay(gaugeDisplay)
                    .gaugeThreshold(alertConfig.gaugeThreshold())
                    .timePeriodSeconds(alertConfig.timePeriodSeconds())
                    .addAllEmailAddresses(alertConfig.emailAddresses())
                    .version(alertConfig.version())
                    .build();
        }
    }
}
