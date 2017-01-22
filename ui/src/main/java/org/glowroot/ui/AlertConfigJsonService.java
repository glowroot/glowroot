/*
 * Copyright 2014-2017 the original author or authors.
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

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.DuplicateMBeanObjectNameException;
import org.glowroot.common.repo.GaugeValueRepository.Gauge;
import org.glowroot.common.repo.util.Compilations;
import org.glowroot.common.repo.util.Compilations.CompilationException;
import org.glowroot.common.repo.util.Encryption;
import org.glowroot.common.repo.util.Gauges;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.Proto.OptionalDouble;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class AlertConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AlertConfigJsonService.class);

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Pattern encryptPattern = Pattern.compile("\"ENCRYPT:([^\"]*)\"");

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

    // central supports alert configs on rollups
    @GET(path = "/backend/config/alerts", permission = "agent:config:view:alert")
    String getAlert(@BindAgentRollupId String agentRollupId,
            @BindRequest AlertConfigRequest request) throws Exception {
        Optional<String> id = request.id();
        if (id.isPresent()) {
            AlertConfig alertConfig = configRepository.getAlertConfig(agentRollupId, id.get());
            if (alertConfig == null) {
                throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
            }
            return mapper.writeValueAsString(AlertConfigDto.create(alertConfig));
        } else {
            List<AlertConfigDto> alertConfigDtos = Lists.newArrayList();
            List<AlertConfig> alertConfigs = configRepository.getAlertConfigs(agentRollupId);
            alertConfigs = orderingByName.immutableSortedCopy(alertConfigs);
            for (AlertConfig alertConfig : alertConfigs) {
                alertConfigDtos.add(AlertConfigDto.create(alertConfig));
            }
            return mapper.writeValueAsString(alertConfigDtos);
        }
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/alerts/add", permission = "agent:config:edit:alert")
    String addAlert(@BindAgentRollupId String agentRollupId, @BindRequest AlertConfigDto configDto)
            throws Exception {
        AlertConfig alertConfig = configDto.convert(configRepository.getSecretKey());
        String errorResponse = validate(alertConfig);
        if (errorResponse != null) {
            return errorResponse;
        }
        String id;
        try {
            id = configRepository.insertAlertConfig(agentRollupId, alertConfig);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        alertConfig = alertConfig.toBuilder()
                .setId(id)
                .build();
        return mapper.writeValueAsString(AlertConfigDto.create(alertConfig));
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/alerts/update", permission = "agent:config:edit:alert")
    String updateAlert(@BindAgentRollupId String agentRollupId,
            @BindRequest AlertConfigDto configDto) throws Exception {
        AlertConfig alertConfig = configDto.convert(configRepository.getSecretKey());
        String errorResponse = validate(alertConfig);
        if (errorResponse != null) {
            return errorResponse;
        }
        configRepository.updateAlertConfig(agentRollupId, alertConfig, configDto.version().get());
        return mapper.writeValueAsString(AlertConfigDto.create(alertConfig));
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/alerts/remove", permission = "agent:config:edit:alert")
    void removeAlert(@BindAgentRollupId String agentRollupId,
            @BindRequest AlertConfigRequest request) throws Exception {
        configRepository.deleteAlertConfig(agentRollupId, request.id().get());
    }

    private @Nullable String validate(AlertConfig alertConfig) throws Exception {
        if (alertConfig.getKind() == AlertKind.SYNTHETIC) {
            // only used by central
            try {
                Class<?> syntheticUserTestClass =
                        Compilations.compile(alertConfig.getSyntheticUserTest());
                try {
                    syntheticUserTestClass.getConstructor();
                } catch (NoSuchMethodException e) {
                    return buildCompilationErrorResponse(
                            ImmutableList.of("Class must have a public default constructor"));
                }
                // since synthetic user test alerts are only used in central, this class is present
                Class<?> webDriverClass = Class.forName("org.openqa.selenium.WebDriver");
                try {
                    syntheticUserTestClass.getMethod("test", new Class[] {webDriverClass});
                } catch (NoSuchMethodException e) {
                    return buildCompilationErrorResponse(ImmutableList.of("Class must have a"
                            + " \"public void test(WebDriver driver) { ... }\" method"));
                }
            } catch (CompilationException e) {
                return buildCompilationErrorResponse(e.getCompilationErrors());
            }
        }
        return null;
    }

    private String buildCompilationErrorResponse(List<String> compilationErrors)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeArrayFieldStart("syntheticUserTestCompilationErrors");
        for (String compilationError : compilationErrors) {
            jg.writeString(compilationError);
        }
        jg.writeEndArray();
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @Value.Immutable
    interface AlertConfigRequest {
        Optional<String> id();
    }

    @Value.Immutable
    abstract static class AlertConfigDto {

        abstract AlertKind kind();

        abstract @Nullable String transactionType();
        abstract @Nullable Double transactionPercentile();
        abstract @Nullable Integer minTransactionCount();
        abstract @Nullable String gaugeName();
        abstract @Nullable Double gaugeThreshold();
        abstract @Nullable String gaugeDisplay(); // only used in response
        abstract List<String> gaugeDisplayPath(); // only used in response
        abstract @Nullable String gaugeUnit(); // only used in response
        abstract @Nullable String gaugeGrouping(); // only used in response
        abstract @Nullable String pingUrl();
        abstract @Nullable String syntheticUserTest();
        abstract @Nullable Integer thresholdMillis();
        abstract @Nullable Integer timePeriodSeconds();
        abstract ImmutableList<String> emailAddresses();
        abstract Optional<String> id(); // absent for insert operations
        abstract Optional<String> version(); // absent for insert operations

        private AlertConfig convert(SecretKey secretKey) throws GeneralSecurityException {
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
            String pingUrl = pingUrl();
            if (pingUrl != null) {
                builder.setPingUrl(pingUrl);
            }
            String syntheticUserTest = syntheticUserTest();
            if (syntheticUserTest != null) {
                Matcher matcher = encryptPattern.matcher(syntheticUserTest);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    String unencryptedPassword = checkNotNull(matcher.group(1));
                    matcher.appendReplacement(sb, "\"ENCRYPTED:"
                            + Encryption.encrypt(unencryptedPassword, secretKey) + "\"");
                }
                matcher.appendTail(sb);
                builder.setSyntheticUserTest(sb.toString());
            }
            Integer thresholdMillis = thresholdMillis();
            if (thresholdMillis != null) {
                builder.setThresholdMillis(OptionalInt32.newBuilder().setValue(thresholdMillis));
            }
            Integer timePeriodSeconds = timePeriodSeconds();
            if (timePeriodSeconds != null) {
                builder.setTimePeriodSeconds(timePeriodSeconds);
            }
            builder.addAllEmailAddress(emailAddresses());
            Optional<String> id = id();
            if (id.isPresent()) {
                builder.setId(id.get());
            }
            return builder.build();
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
            String pingUrl = alertConfig.getPingUrl();
            if (!pingUrl.isEmpty()) {
                builder.pingUrl(pingUrl);
            }
            String syntheticUserTest = alertConfig.getSyntheticUserTest();
            if (!syntheticUserTest.isEmpty()) {
                builder.syntheticUserTest(syntheticUserTest);
            }
            if (alertConfig.hasThresholdMillis()) {
                builder.thresholdMillis(alertConfig.getThresholdMillis().getValue());
            }
            int timePeriodSeconds = alertConfig.getTimePeriodSeconds();
            if (timePeriodSeconds != 0) {
                builder.timePeriodSeconds(timePeriodSeconds);
            }
            return builder.gaugeUnit(gauge == null ? "" : gauge.unit())
                    .gaugeGrouping(gauge == null ? "" : gauge.grouping())
                    .addAllEmailAddresses(alertConfig.getEmailAddressList())
                    .id(alertConfig.getId())
                    .version(Versions.getVersion(alertConfig))
                    .build();
        }
    }
}
