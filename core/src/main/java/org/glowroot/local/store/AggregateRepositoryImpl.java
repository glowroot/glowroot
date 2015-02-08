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
package org.glowroot.local.store;

import java.util.List;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateRepository;

class AggregateRepositoryImpl implements AggregateRepository {

    private final AggregateDao aggregateDao;
    private final AlertingService alertingService;

    AggregateRepositoryImpl(AggregateDao aggregateDao, AlertingService alertingService) {
        this.aggregateDao = aggregateDao;
        this.alertingService = alertingService;
    }

    @Override
    public void store(List<Aggregate> overallAggregates, List<Aggregate> transactionAggregates,
            long captureTime) throws Exception {
        aggregateDao.store(overallAggregates, transactionAggregates, captureTime);
        alertingService.checkAlerts(captureTime);
    }
}
