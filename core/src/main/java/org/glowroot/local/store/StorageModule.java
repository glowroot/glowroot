/*
 * Copyright 2011-2015 the original author or authors.
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

import java.io.File;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.collector.AggregateRepository;
import org.glowroot.collector.TraceRepository;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.weaving.PreInitializeStorageShutdownClasses;

import static java.util.concurrent.TimeUnit.MINUTES;

public class StorageModule {

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private static final long fixedGaugeRollupSeconds =
            Long.getLong("glowroot.internal.gaugeRollup1", 60);
    private static final long fixedAggregateRollupSeconds =
            Long.getLong("glowroot.internal.aggregateRollup1", 300);

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;
    private final AggregateDao aggregateDao;
    private final AggregateRepositoryImpl aggregateRepositoryImpl;
    private final TraceDao traceDao;
    private final GaugePointDao gaugePointDao;
    private final @Nullable ReaperRunnable reaperRunnable;

    public StorageModule(File dataDir, Map<String, String> properties, Ticker ticker, Clock clock,
            ConfigModule configModule, ScheduledExecutorService scheduledExecutor,
            boolean viewerModeEnabled) throws Exception {
        // mem db is only used for testing (by glowroot-test-container)
        String h2MemDb = properties.get("internal.h2.memdb");
        final DataSource dataSource;
        if (Boolean.parseBoolean(h2MemDb)) {
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "glowroot.mv.db"));
        }
        final ConfigService configService = configModule.getConfigService();
        dataSource.setQueryTimeoutSeconds(
                configService.getAdvancedConfig().internalQueryTimeoutSeconds());
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                dataSource.setQueryTimeoutSeconds(
                        configService.getAdvancedConfig().internalQueryTimeoutSeconds());
            }
        });
        this.dataSource = dataSource;
        int cappedDatabaseSizeMb = configService.getStorageConfig().cappedDatabaseSizeMb();
        cappedDatabase = new CappedDatabase(new File(dataDir, "glowroot.capped.db"),
                cappedDatabaseSizeMb * 1024, scheduledExecutor, ticker);
        aggregateDao = new AggregateDao(dataSource, cappedDatabase, fixedAggregateRollupSeconds);
        TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(dataSource);
        AlertingService alertingService =
                new AlertingService(configService, triggeredAlertDao, aggregateDao);
        aggregateRepositoryImpl = new AggregateRepositoryImpl(aggregateDao, alertingService);
        traceDao = new TraceDao(dataSource, cappedDatabase);
        gaugePointDao = new GaugePointDao(dataSource, clock, fixedGaugeRollupSeconds);
        PreInitializeStorageShutdownClasses.preInitializeClasses();
        if (viewerModeEnabled) {
            reaperRunnable = null;
        } else {
            reaperRunnable = new ReaperRunnable(configService, aggregateDao, traceDao,
                    gaugePointDao, clock);
            reaperRunnable.scheduleWithFixedDelay(scheduledExecutor, 0,
                    SNAPSHOT_REAPER_PERIOD_MINUTES, MINUTES);
        }
    }

    public AggregateRepository getAggregateRepository() {
        return aggregateRepositoryImpl;
    }

    public TraceRepository getTraceRepository() {
        return traceDao;
    }

    public GaugePointDao getGaugePointDao() {
        return gaugePointDao;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public AggregateDao getAggregateDao() {
        return aggregateDao;
    }

    public TraceDao getTraceDao() {
        return traceDao;
    }

    public CappedDatabase getCappedDatabase() {
        return cappedDatabase;
    }

    public long getFixedAggregateRollupSeconds() {
        return fixedAggregateRollupSeconds;
    }

    public long getFixedGaugeRollupSeconds() {
        return fixedGaugeRollupSeconds;
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (reaperRunnable != null) {
            reaperRunnable.cancel();
        }
        cappedDatabase.close();
        dataSource.close();
    }
}
