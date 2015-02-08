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

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.common.marshal.Marshaling;
import org.immutables.value.Json;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Marshaling2;
import org.glowroot.config.AlertConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ConfigService.DuplicateMBeanObjectNameException;
import org.glowroot.config.ImmutableAlertConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class AlertJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AlertJsonService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConfigService configService;

    AlertJsonService(ConfigService configService) {
        this.configService = configService;
    }

    @GET("/backend/config/alerts")
    String getAlertList() {
        List<AlertConfigDto> alertConfigDtos = Lists.newArrayList();
        List<AlertConfig> alertConfigs = configService.getAlertConfigs();
        alertConfigs = AlertConfig.orderingByName.immutableSortedCopy(alertConfigs);
        for (AlertConfig alertConfig : alertConfigs) {
            alertConfigDtos.add(AlertConfigDto.fromConfig(alertConfig));
        }
        return Marshaling2.toJson(alertConfigDtos, AlertConfigDto.class);
    }

    @GET("/backend/config/alerts/([0-9a-f]{40})")
    String getAlert(String version) {
        AlertConfig alertConfig = configService.getAlertConfig(version);
        if (alertConfig == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        return Marshaling2.toJson(AlertConfigDto.fromConfig(alertConfig));
    }

    @POST("/backend/config/alerts/add")
    String addAlert(String content) throws Exception {
        AlertConfigDto alertConfigDto = Marshaling.fromJson(content, AlertConfigDto.class);
        AlertConfig alertConfig = alertConfigDto.toConfig();
        try {
            configService.insertAlertConfig(alertConfig);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        return Marshaling2.toJson(AlertConfigDto.fromConfig(alertConfig));
    }

    @POST("/backend/config/alerts/update")
    String updateAlert(String content) throws IOException {
        AlertConfigDto alertConfigDto = Marshaling.fromJson(content, AlertConfigDto.class);
        AlertConfig alertConfig = alertConfigDto.toConfig();
        String version = alertConfigDto.version();
        checkNotNull(version, "Missing required request property: version");
        configService.updateAlertConfig(alertConfig, version);
        return Marshaling2.toJson(AlertConfigDto.fromConfig(alertConfig));
    }

    @POST("/backend/config/alerts/remove")
    void removeAlert(String content) throws IOException {
        String version = mapper.readValue(content, String.class);
        checkNotNull(version);
        configService.deleteAlertConfig(version);
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class AlertConfigDto {

        public abstract String transactionType();
        public abstract double percentile();
        public abstract int timePeriodMinutes();
        public abstract int thresholdMillis();
        public abstract int minTransactionCount();
        public abstract List<String> emailAddresses();
        abstract @Nullable String version(); // null for insert operations

        private static AlertConfigDto fromConfig(AlertConfig alertConfig) {
            return ImmutableAlertConfigDto.builder()
                    .transactionType(alertConfig.transactionType())
                    .percentile(alertConfig.percentile())
                    .timePeriodMinutes(alertConfig.timePeriodMinutes())
                    .thresholdMillis(alertConfig.thresholdMillis())
                    .minTransactionCount(alertConfig.minTransactionCount())
                    .addAllEmailAddresses(alertConfig.emailAddresses())
                    .version(alertConfig.version())
                    .build();
        }

        private AlertConfig toConfig() {
            return ImmutableAlertConfig.builder()
                    .transactionType(transactionType())
                    .percentile(percentile())
                    .timePeriodMinutes(timePeriodMinutes())
                    .thresholdMillis(thresholdMillis())
                    .minTransactionCount(minTransactionCount())
                    .addAllEmailAddresses(emailAddresses())
                    .build();
        }
    }
}
