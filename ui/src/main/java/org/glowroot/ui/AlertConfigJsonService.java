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

import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.DuplicateMBeanObjectNameException;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.GaugeValueRepository.Gauge;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.repo.util.Gauges;
import org.glowroot.common.util.Formatting;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Styles;
import org.glowroot.common.util.Versions;
import org.glowroot.ui.GaugeValueJsonService.GaugeOrdering;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;
import org.glowroot.wire.api.model.Proto.OptionalDouble;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class AlertConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AlertConfigJsonService.class);

    private static final ObjectMapper mapper = ObjectMappers.create();

    @VisibleForTesting
    static final Ordering<AlertListItem> orderingByName = new Ordering<AlertListItem>() {
        @Override
        public int compare(AlertListItem left, AlertListItem right) {
            return left.display().compareToIgnoreCase(right.display());
        }
    };

    private final ConfigRepository configRepository;
    private final GaugeValueRepository gaugeValueRepository;

    AlertConfigJsonService(ConfigRepository configRepository,
            GaugeValueRepository gaugeValueRepository) {
        this.configRepository = configRepository;
        this.gaugeValueRepository = gaugeValueRepository;
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
            return getAlertResponse(agentRollupId, alertConfig);
        } else {
            List<AlertListItem> alertListItems = Lists.newArrayList();
            List<AlertConfig> alertConfigs = configRepository.getAlertConfigs(agentRollupId);
            for (AlertConfig alertConfig : alertConfigs) {
                alertListItems.add(ImmutableAlertListItem.of(alertConfig.getId(),
                        getAlertDisplay(agentRollupId, alertConfig, configRepository)));
            }
            alertListItems = orderingByName.immutableSortedCopy(alertListItems);
            return mapper.writeValueAsString(alertListItems);
        }
    }

    // central supports alert configs on rollups
    @GET(path = "/backend/config/alert-dropdowns", permission = "agent:config:view:alert")
    String getAlertDropdowns(@BindAgentRollupId String agentRollupId) throws Exception {
        return mapper.writeValueAsString(ImmutableAlertConfigResponse.builder()
                .addAllGauges(getGaugeDropdownItems(agentRollupId))
                .addAllSyntheticMonitors(getSyntheticMonitorDropdownItems(agentRollupId))
                .build());
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/alerts/add", permission = "agent:config:edit:alert")
    String addAlert(@BindAgentRollupId String agentRollupId, @BindRequest AlertConfigDto configDto)
            throws Exception {
        AlertConfig alertConfig = configDto.convert();
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
        AlertConfig alertConfig = configDto.convert();
        configRepository.updateAlertConfig(agentRollupId, alertConfig, configDto.version().get());
        return getAlertResponse(agentRollupId, alertConfig);
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/alerts/remove", permission = "agent:config:edit:alert")
    void removeAlert(@BindAgentRollupId String agentRollupId,
            @BindRequest AlertConfigRequest request) throws Exception {
        configRepository.deleteAlertConfig(agentRollupId, request.id().get());
    }

    private String getAlertResponse(String agentRollupId, AlertConfig alertConfig)
            throws Exception {
        return mapper.writeValueAsString(ImmutableAlertConfigResponse.builder()
                .config(AlertConfigDto.create(alertConfig))
                .heading(getAlertDisplay(agentRollupId, alertConfig, configRepository))
                .addAllGauges(getGaugeDropdownItems(agentRollupId))
                .addAllSyntheticMonitors(getSyntheticMonitorDropdownItems(agentRollupId))
                .build());
    }

    private List<Gauge> getGaugeDropdownItems(String agentRollupId) throws Exception {
        List<Gauge> gauges = gaugeValueRepository.getGauges(agentRollupId);
        return new GaugeOrdering().immutableSortedCopy(gauges);
    }

    private List<SyntheticMonitorItem> getSyntheticMonitorDropdownItems(String agentRollupId)
            throws Exception {
        List<SyntheticMonitorItem> items = Lists.newArrayList();
        for (SyntheticMonitorConfig config : configRepository
                .getSyntheticMonitorConfigs(agentRollupId)) {
            items.add(ImmutableSyntheticMonitorItem.of(config.getId(), config.getDisplay()));
        }
        return items;
    }

    static String getAlertDisplay(String agentRollupId, AlertConfig alertConfig,
            ConfigRepository configRepository) throws Exception {
        switch (alertConfig.getKind()) {
            case TRANSACTION:
                return getTransactionAlertDisplay(alertConfig);
            case GAUGE:
                return getGaugeAlertDisplay(alertConfig);
            case HEARTBEAT:
                return getHeartbeatAlertDisplay(alertConfig);
            case SYNTHETIC_MONITOR:
                SyntheticMonitorConfig syntheticMonitorConfig =
                        configRepository.getSyntheticMonitorConfig(agentRollupId,
                                alertConfig.getSyntheticMonitorId());
                return getSyntheticMonitorAlertDisplay(alertConfig, syntheticMonitorConfig);
            default:
                throw new IllegalStateException("Unexpected alert kind: " + alertConfig.getKind());
        }
    }

    private static String getTransactionAlertDisplay(AlertConfig alertConfig) {
        StringBuilder sb = new StringBuilder();
        sb.append(alertConfig.getTransactionType());
        sb.append(" - ");
        sb.append(Utils.getPercentileWithSuffix(alertConfig.getTransactionPercentile().getValue()));
        sb.append(" percentile over the last ");
        sb.append(alertConfig.getTimePeriodSeconds() / 60);
        sb.append(" minute");
        if (alertConfig.getTimePeriodSeconds() != 60) {
            sb.append("s");
        }
        sb.append(" exceeds ");
        int thresholdMillis = alertConfig.getThresholdMillis().getValue();
        sb.append(thresholdMillis);
        sb.append(" millisecond");
        if (thresholdMillis != 1) {
            sb.append("s");
        }
        return sb.toString();
    }

    private static String getGaugeAlertDisplay(AlertConfig alertConfig) {
        Gauge gauge = Gauges.getGauge(alertConfig.getGaugeName());
        StringBuilder sb = new StringBuilder();
        sb.append("Gauge - ");
        sb.append(gauge.display());
        sb.append(" - average over the last ");
        sb.append(alertConfig.getTimePeriodSeconds() / 60);
        sb.append(" minute");
        if (alertConfig.getTimePeriodSeconds() != 60) {
            sb.append("s");
        }
        sb.append(" exceeds ");
        double value = alertConfig.getGaugeThreshold().getValue();
        String unit = gauge.unit();
        if (unit.equals("bytes")) {
            sb.append(Formatting.formatBytes((long) value));
        } else if (!unit.isEmpty()) {
            sb.append(Formatting.displaySixDigitsOfPrecision(value));
            sb.append(" ");
            sb.append(unit);
        } else {
            sb.append(Formatting.displaySixDigitsOfPrecision(value));
        }
        return sb.toString();
    }

    private static String getHeartbeatAlertDisplay(AlertConfig alertConfig) {
        StringBuilder sb = new StringBuilder();
        sb.append("Heartbeat - not received over the last ");
        int timePeriodSeconds = alertConfig.getTimePeriodSeconds();
        sb.append(timePeriodSeconds);
        sb.append(" second");
        if (timePeriodSeconds != 1) {
            sb.append("s");
        }
        return sb.toString();
    }

    private static String getSyntheticMonitorAlertDisplay(AlertConfig alertConfig,
            @Nullable SyntheticMonitorConfig syntheticMonitorConfig) {
        StringBuilder sb = new StringBuilder();
        sb.append("Synthetic monitor - ");
        if (syntheticMonitorConfig == null) {
            sb.append("<NOT FOUND>");
        } else {
            sb.append(syntheticMonitorConfig.getDisplay());
        }
        sb.append(" exceeds ");
        int thresholdMillis = alertConfig.getThresholdMillis().getValue();
        sb.append(thresholdMillis);
        sb.append(" millisecond");
        if (thresholdMillis != 1) {
            sb.append("s");
        }
        return sb.toString();
    }

    @Value.Immutable
    interface AlertConfigRequest {
        Optional<String> id();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface AlertListItem {
        String id();
        String display();
    }

    @Value.Immutable
    interface AlertConfigResponse {
        @Nullable
        AlertConfigDto config();
        @Nullable
        String heading();
        List<Gauge> gauges();
        List<SyntheticMonitorItem> syntheticMonitors();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface SyntheticMonitorItem {
        String id();
        String display();
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
        abstract @Nullable String syntheticMonitorId();
        abstract @Nullable Integer thresholdMillis();
        abstract @Nullable Integer timePeriodSeconds();
        abstract ImmutableList<String> emailAddresses();
        abstract Optional<String> id(); // absent for insert operations
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
            String syntheticMonitorId = syntheticMonitorId();
            if (syntheticMonitorId != null) {
                builder.setSyntheticMonitorId(syntheticMonitorId);
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
            String syntheticMonitorId = alertConfig.getSyntheticMonitorId();
            if (!syntheticMonitorId.isEmpty()) {
                builder.syntheticMonitorId(syntheticMonitorId);
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
