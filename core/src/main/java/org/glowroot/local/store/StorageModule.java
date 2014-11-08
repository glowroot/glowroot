/*
 * Copyright 2011-2014 the original author or authors.
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
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(StorageModule.class);

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugePointDao gaugePointDao;
    @Nullable
    private final ReaperRunnable reaperRunnable;

    public StorageModule(File dataDir, Map<String, String> properties, Ticker ticker, Clock clock,
            ConfigModule configModule, ScheduledExecutorService scheduledExecutor,
            boolean viewerModeEnabled) throws SQLException, IOException {
        // mem db is only used for testing (by glowroot-test-container)
        String h2MemDb = properties.get("internal.h2.memdb");
        final DataSource dataSource;
        if (Boolean.parseBoolean(h2MemDb)) {
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "glowroot.h2.db"));
        }
        final ConfigService configService = configModule.getConfigService();
        dataSource.setQueryTimeoutSeconds(
                configService.getAdvancedConfig().getInternalQueryTimeoutSeconds());
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                dataSource.setQueryTimeoutSeconds(
                        configService.getAdvancedConfig().getInternalQueryTimeoutSeconds());
            }
        });
        this.dataSource = dataSource;
        int cappedDatabaseSizeMb = configService.getStorageConfig().getCappedDatabaseSizeMb();
        cappedDatabase = new CappedDatabase(new File(dataDir, "glowroot.capped.db"),
                cappedDatabaseSizeMb * 1024, scheduledExecutor, ticker);
        aggregateDao = new AggregateDao(dataSource, cappedDatabase);
        traceDao = new TraceDao(dataSource, cappedDatabase);
        gaugePointDao = new GaugePointDao(dataSource);
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
        return aggregateDao;
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

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
        if (reaperRunnable != null) {
            reaperRunnable.cancel();
        }
        try {
            cappedDatabase.close();
        } catch (IOException e) {
            // warning only since it occurs during shutdown anyways
            logger.warn(e.getMessage(), e);
        }
        try {
            dataSource.close();
        } catch (SQLException e) {
            // warning only since it occurs during shutdown anyways
            logger.warn(e.getMessage(), e);
        }
    }
}
