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

import checkers.igj.quals.ReadOnly;
import com.google.common.base.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.AggregateRepository;
import org.glowroot.collector.SnapshotRepository;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class StorageModule {

    private static final Logger logger = LoggerFactory.getLogger(StorageModule.class);

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 60;

    private final DataSource dataSource;
    private final RollingFile rollingFile;
    private final SnapshotDao snapshotDao;
    private final ReaperScheduledRunnable reaperScheduledRunnable;
    private final AggregateDao aggregateDao;

    public StorageModule(File dataDir, @ReadOnly Map<String, String> properties, Ticker ticker,
            Clock clock, ConfigModule configModule, ScheduledExecutorService scheduledExecutor)
            throws SQLException, IOException {
        // mem db is only used for testing (by glowroot-test-container)
        String h2MemDb = properties.get("internal.h2.memdb");
        if (Boolean.parseBoolean(h2MemDb)) {
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "glowroot.h2.db"));
        }
        ConfigService configService = configModule.getConfigService();
        int rollingSizeMb = configService.getStorageConfig().getRollingSizeMb();
        rollingFile = new RollingFile(new File(dataDir, "glowroot.rolling.db"),
                rollingSizeMb * 1024, scheduledExecutor, ticker);
        snapshotDao = new SnapshotDao(dataSource, rollingFile);
        reaperScheduledRunnable =
                new ReaperScheduledRunnable(configService, snapshotDao, clock);
        reaperScheduledRunnable.scheduleAtFixedRate(scheduledExecutor, 0,
                SNAPSHOT_REAPER_PERIOD_MINUTES, MINUTES);
        aggregateDao = new AggregateDao(dataSource);
    }

    public AggregateRepository getAggregateRepository() {
        return aggregateDao;
    }

    public SnapshotRepository getSnapshotRepository() {
        return snapshotDao;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public AggregateDao getAggregateDao() {
        return aggregateDao;
    }

    public SnapshotDao getSnapshotDao() {
        return snapshotDao;
    }

    public RollingFile getRollingFile() {
        return rollingFile;
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
        reaperScheduledRunnable.cancel();
        try {
            rollingFile.close();
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
