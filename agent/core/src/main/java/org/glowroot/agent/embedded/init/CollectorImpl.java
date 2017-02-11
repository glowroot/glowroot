/*
 * Copyright 2015-2017 the original author or authors.
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

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.embedded.repo.AggregateDao;
import org.glowroot.agent.embedded.repo.EnvironmentDao;
import org.glowroot.agent.embedded.repo.GaugeValueDao;
import org.glowroot.agent.embedded.repo.TraceDao;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class CollectorImpl implements Collector {

    private final EnvironmentDao agentDao;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;

    CollectorImpl(EnvironmentDao agentDao, AggregateDao aggregateRepository,
            TraceDao traceRepository, GaugeValueDao gaugeValueRepository) {
        this.agentDao = agentDao;
        this.aggregateDao = aggregateRepository;
        this.traceDao = traceRepository;
        this.gaugeValueDao = gaugeValueRepository;
    }

    @Override
    public void init(File glowrootDir, File agentDir, Environment environment,
            AgentConfig agentConfig, AgentConfigUpdater agentConfigUpdater) throws Exception {
        agentDao.store(environment);
    }

    @Override
    public void collectAggregates(long captureTime, Aggregates aggregates) throws Exception {
        aggregateDao.store(captureTime, aggregates);
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        gaugeValueDao.store(gaugeValues);
        long maxCaptureTime = 0;
        for (GaugeValue gaugeValue : gaugeValues) {
            maxCaptureTime = Math.max(maxCaptureTime, gaugeValue.getCaptureTime());
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
