/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.server.simplerepo;

import java.util.List;

import org.glowroot.collector.spi.Collector;
import org.glowroot.collector.spi.model.AggregateOuterClass.OverallAggregate;
import org.glowroot.collector.spi.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.collector.spi.model.GaugeValueOuterClass.GaugeValue;
import org.glowroot.collector.spi.model.TraceOuterClass.Trace;
import org.glowroot.server.repo.helper.AlertingService;

class CollectorImpl implements Collector {

    private static final long SERVER_ID = 0;

    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final AlertingService alertingService;

    CollectorImpl(AggregateDao aggregateDao, TraceDao traceDao, GaugeValueDao gaugeValueDao,
            AlertingService alertingService) {
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.gaugeValueDao = gaugeValueDao;
        this.alertingService = alertingService;
    }

    @Override
    public void collectAggregates(long captureTime, List<OverallAggregate> overallAggregates,
            List<TransactionAggregate> transactionAggregates) throws Exception {
        aggregateDao.store(SERVER_ID, captureTime, overallAggregates, transactionAggregates);
        alertingService.checkAlerts(captureTime);
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        gaugeValueDao.store(SERVER_ID, gaugeValues);
    }

    @Override
    public void collectTrace(Trace trace) throws Exception {
        traceDao.collect(SERVER_ID, trace);
    }
}
