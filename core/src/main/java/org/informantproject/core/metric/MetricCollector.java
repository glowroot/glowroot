/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.metric;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.informantproject.api.PluginServices.ConfigurationListener;
import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.util.DaemonExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Owns the thread (via a single threaded scheduled executor) that performs metric collection.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class MetricCollector implements Runnable, ConfigurationListener {

    private static final Logger logger = LoggerFactory.getLogger(MetricCollector.class);

    private final ScheduledExecutorService scheduledExecutor =
            DaemonExecutors.newSingleThreadScheduledExecutor("Informant-MetricCollector");

    private final ConfigurationService configurationService;
    private final MetricSink metricSink;

    private volatile Future<?> future;
    private volatile int bossIntervalMillis;

    @Inject
    public MetricCollector(ConfigurationService configurationService, MetricSink metricSink) {
        this.configurationService = configurationService;
        this.metricSink = metricSink;
        bossIntervalMillis = configurationService.getCoreConfiguration().getMetricPeriodMillis();
        future = scheduledExecutor.scheduleWithFixedDelay(this, 0, bossIntervalMillis,
                TimeUnit.MILLISECONDS);
        configurationService.addConfigurationListener(this);
    }

    public void run() {
        try {
            runInternal();
        } catch (Exception e) {
            // log and terminate this thread successfully
            logger.error(e.getMessage(), e);
        } catch (Error e) {
            // log and re-throw serious error which will terminate subsequent scheduled executions
            // (see ScheduledExecutorService.scheduleWithFixedDelay())
            logger.error(e.getMessage(), e);
            throw e;
        }
    }

    public void onChange() {
        int updatedBossIntervalMillis = configurationService.getCoreConfiguration()
                .getMetricPeriodMillis();
        if (updatedBossIntervalMillis != bossIntervalMillis) {
            bossIntervalMillis = updatedBossIntervalMillis;
            future.cancel(false);
            try {
                // wait for a potentially running command to complete
                // in order to avoid two commands running concurrently
                future.get();
            } catch (CancellationException e) {
                // this is good
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            } catch (ExecutionException e) {
                logger.error(e.getMessage(), e.getCause());
            }
            future = scheduledExecutor.scheduleWithFixedDelay(this, 0, bossIntervalMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    public void shutdown() {
        logger.debug("shutdown()");
        future.cancel(true);
        scheduledExecutor.shutdownNow();
    }

    private void runInternal() {
        // RuntimeMXBean mxbean = ManagementFactory.getRuntimeMXBean();
        List<MetricValue> metricValues = Lists.newArrayList();
        addMemoryStats(metricValues);
        metricSink.onMetricCapture(metricValues);
    }

    private static void addMemoryStats(List<MetricValue> metricValues) {
        MemoryMXBean mxbean = ManagementFactory.getMemoryMXBean();
        long memInit = mxbean.getHeapMemoryUsage().getInit();
        long memUsed = mxbean.getHeapMemoryUsage().getUsed();
        long memCommitted = mxbean.getHeapMemoryUsage().getCommitted();
        long memMax = mxbean.getHeapMemoryUsage().getMax();
        metricValues.add(new MetricValue("mem.init", memInit));
        metricValues.add(new MetricValue("mem.used", memUsed));
        metricValues.add(new MetricValue("mem.committed", memCommitted));
        metricValues.add(new MetricValue("mem.max", memMax));
    }
}
