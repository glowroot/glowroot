/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.fat.init;

import java.io.File;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.fat.storage.AgentDao;
import org.glowroot.agent.fat.storage.AggregateDao;
import org.glowroot.agent.fat.storage.GaugeValueDao;
import org.glowroot.agent.fat.storage.TraceDao;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class CollectorImpl implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorImpl.class);

    private static final String AGENT_ID = "";

    private final AgentDao agentDao;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final ConfigRepository configRepository;
    private final AlertingService alertingService;

    CollectorImpl(AgentDao agentDao, AggregateDao aggregateRepository, TraceDao traceRepository,
            GaugeValueDao gaugeValueRepository, ConfigRepository configRepository,
            AlertingService alertingService) {
        this.agentDao = agentDao;
        this.aggregateDao = aggregateRepository;
        this.traceDao = traceRepository;
        this.gaugeValueDao = gaugeValueRepository;
        this.configRepository = configRepository;
        this.alertingService = alertingService;
    }

    @Override
    public void init(File glowrootBaseDir, Environment environment, AgentConfig agentConfig,
            AgentConfigUpdater agentConfigUpdater) throws Exception {
        agentDao.store(environment);
    }

    @Override
    public void collectAggregates(long captureTime, Aggregates aggregates) throws Exception {
        aggregateDao.store(captureTime, aggregates);
        SmtpConfig smtpConfig = configRepository.getSmtpConfig();
        if (smtpConfig.host().isEmpty()) {
            return;
        }
        for (AlertConfig alertConfig : configRepository.getTransactionAlertConfigs(AGENT_ID)) {
            try {
                alertingService.checkTransactionAlert(AGENT_ID, alertConfig, captureTime,
                        smtpConfig);
            } catch (InterruptedException e) {
                // shutdown request
                throw e;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        gaugeValueDao.store(gaugeValues);
        SmtpConfig smtpConfig = configRepository.getSmtpConfig();
        if (smtpConfig.host().isEmpty()) {
            return;
        }
        long maxCaptureTime = 0;
        for (GaugeValue gaugeValue : gaugeValues) {
            maxCaptureTime = Math.max(maxCaptureTime, gaugeValue.getCaptureTime());
        }
        for (AlertConfig alertConfig : configRepository.getGaugeAlertConfigs(AGENT_ID)) {
            try {
                alertingService.checkGaugeAlert(AGENT_ID, alertConfig, maxCaptureTime, smtpConfig);
            } catch (InterruptedException e) {
                // shutdown request
                throw e;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void collectTrace(Trace trace) throws Exception {
        traceDao.store(trace);
    }

    @Override
    public void log(LogEvent logEvent) {
        // do nothing, already logging locally through ConsoleAppender and RollingFileAppender
    }
}
