/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.agent.embedded.init;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.embedded.repo.AggregateDao;
import org.glowroot.agent.embedded.repo.ConfigRepositoryImpl;
import org.glowroot.agent.embedded.repo.EnvironmentDao;
import org.glowroot.agent.embedded.repo.GaugeValueDao;
import org.glowroot.agent.embedded.repo.TraceDao;
import org.glowroot.common.config.HealthchecksIoConfig;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.common.repo.util.HttpClient;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;

class CollectorImpl implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorImpl.class);

    private static final String AGENT_ID = "";

    private final EnvironmentDao environmentDao;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final ConfigRepositoryImpl configRepository;
    private final AlertingService alertingService;
    private final HttpClient httpClient;

    CollectorImpl(EnvironmentDao environmentDao, AggregateDao aggregateDao,
            TraceDao traceRepository, GaugeValueDao gaugeValueRepository,
            ConfigRepositoryImpl configRepository, AlertingService alertingService,
            HttpClient httpClient) {
        this.environmentDao = environmentDao;
        this.aggregateDao = aggregateDao;
        this.traceDao = traceRepository;
        this.gaugeValueDao = gaugeValueRepository;
        this.configRepository = configRepository;
        this.alertingService = alertingService;
        this.httpClient = httpClient;
    }

    @Override
    public void init(File confDir, @Nullable File sharedConfDir, Environment environment,
            AgentConfig agentConfig, AgentConfigUpdater agentConfigUpdater) throws Exception {
        environmentDao.store(environment);
    }

    @Override
    public void collectAggregates(AggregateReader aggregateReader) throws Exception {
        aggregateDao.store(aggregateReader);
        alertingService.checkForDeletedAlerts(AGENT_ID);
        for (AlertConfig alertConfig : configRepository.getAlertConfigs(AGENT_ID)) {
            AlertCondition alertCondition = alertConfig.getCondition();
            if (isAggregateMetricCondition(alertCondition)) {
                try {
                    alertingService.checkMetricAlert("", AGENT_ID,
                            configRepository.getEmbeddedAdminGeneralConfig()
                                    .agentDisplayNameOrDefault(),
                            alertConfig, alertCondition.getMetricCondition(),
                            aggregateReader.captureTime());
                } catch (InterruptedException e) {
                    // probably shutdown requested
                    throw e;
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        HealthchecksIoConfig healthchecksIoConfig = configRepository.getHealthchecksIoConfig();
        String healthchecksIoPingUrl = healthchecksIoConfig.pingUrl();
        if (!healthchecksIoPingUrl.isEmpty()) {
            try {
                httpClient.get(healthchecksIoPingUrl);
            } catch (Exception e) {
                logger.error("error sending ping to healthchecks.io: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        gaugeValueDao.store(gaugeValues);
        long maxCaptureTime = 0;
        for (GaugeValue gaugeValue : gaugeValues) {
            maxCaptureTime = Math.max(maxCaptureTime, gaugeValue.getCaptureTime());
        }
        alertingService.checkForDeletedAlerts(AGENT_ID);
        for (AlertConfig alertConfig : configRepository.getAlertConfigs(AGENT_ID)) {
            AlertCondition alertCondition = alertConfig.getCondition();
            if (isGaugeMetricCondition(alertCondition)) {
                try {
                    alertingService.checkMetricAlert("", AGENT_ID,
                            configRepository.getEmbeddedAdminGeneralConfig()
                                    .agentDisplayNameOrDefault(),
                            alertConfig, alertCondition.getMetricCondition(), maxCaptureTime);
                } catch (InterruptedException e) {
                    // probably shutdown requested
                    throw e;
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void collectTrace(TraceReader traceReader) throws Exception {
        traceDao.store(traceReader);
    }

    @Override
    public void log(LogEvent logEvent) {
        // do nothing, already logging locally through ConsoleAppender and RollingFileAppender
    }

    private static boolean isAggregateMetricCondition(AlertCondition alertCondition) {
        if (alertCondition.getValCase() != AlertCondition.ValCase.METRIC_CONDITION) {
            return false;
        }
        String metric = alertCondition.getMetricCondition().getMetric();
        return metric.startsWith("transaction:") || metric.startsWith("error:");
    }

    private static boolean isGaugeMetricCondition(AlertCondition alertCondition) {
        return alertCondition.getValCase() == AlertCondition.ValCase.METRIC_CONDITION
                && alertCondition.getMetricCondition().getMetric().startsWith("gauge:");
    }
}
