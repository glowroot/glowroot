/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.transaction;

import org.glowroot.api.MetricName;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.transaction.model.Transaction;
import org.glowroot.transaction.model.TransactionMetricExt;
import org.glowroot.transaction.model.TransactionMetricImpl;
import org.glowroot.weaving.WeavingTimerService;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class WeavingTimerServiceImpl implements WeavingTimerService {

    private final TransactionRegistry transactionRegistry;
    private final MetricName metricName;

    WeavingTimerServiceImpl(TransactionRegistry transactionRegistry,
            MetricNameCache metricNameCache) {
        this.transactionRegistry = transactionRegistry;
        this.metricName = metricNameCache.getName(OnlyForTheMetricName.class);
    }

    @Override
    public WeavingTimer start() {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopWeavingTimer.INSTANCE;
        }
        TransactionMetricImpl currentMetric = transaction.getCurrentTransactionMetric();
        if (currentMetric == null) {
            return NopWeavingTimer.INSTANCE;
        }
        final TransactionMetricExt transactionMetric = currentMetric.startNestedMetric(metricName);
        return new WeavingTimer() {
            @Override
            public void stop() {
                transactionMetric.stop();
            }
        };
    }

    @ThreadSafe
    private static class NopWeavingTimer implements WeavingTimer {
        private static final NopWeavingTimer INSTANCE = new NopWeavingTimer();
        @Override
        public void stop() {}
    }

    @Pointcut(className = "", methodName = "", metricName = "glowroot weaving")
    private static class OnlyForTheMetricName {}
}
