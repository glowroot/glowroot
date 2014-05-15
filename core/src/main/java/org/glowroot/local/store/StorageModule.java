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

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.SnapshotRepository;
import org.glowroot.collector.TransactionPointRepository;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.weaving.PreInitializeStorageShutdownClasses;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class StorageModule {

    private static final Logger logger = LoggerFactory.getLogger(StorageModule.class);

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;
    private final TransactionPointDao transactionPointDao;
    private final SnapshotDao snapshotDao;
    @Nullable
    private final ReaperScheduledRunnable reaperScheduledRunnable;

    public StorageModule(File dataDir, Map<String, String> properties, Ticker ticker, Clock clock,
            ConfigModule configModule, ScheduledExecutorService scheduledExecutor,
            boolean snapshotReaperDisabled) throws SQLException, IOException {
        // mem db is only used for testing (by glowroot-test-container)
        String h2MemDb = properties.get("internal.h2.memdb");
        if (Boolean.parseBoolean(h2MemDb)) {
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "glowroot.h2.db"));
        }
        ConfigService configService = configModule.getConfigService();
        int cappedDatabaseSizeMb = configService.getStorageConfig().getCappedDatabaseSizeMb();
        cappedDatabase = new CappedDatabase(new File(dataDir, "glowroot.capped.db"),
                cappedDatabaseSizeMb * 1024, scheduledExecutor, ticker);
        transactionPointDao = new TransactionPointDao(dataSource, cappedDatabase);
        snapshotDao = new SnapshotDao(dataSource, cappedDatabase);
        PreInitializeStorageShutdownClasses.preInitializeClasses(
                StorageModule.class.getClassLoader());

        if (snapshotReaperDisabled) {
            reaperScheduledRunnable = null;
        } else {
            reaperScheduledRunnable = new ReaperScheduledRunnable(configService,
                    transactionPointDao, snapshotDao, clock);
            reaperScheduledRunnable.scheduleAtFixedRate(scheduledExecutor, 0,
                    SNAPSHOT_REAPER_PERIOD_MINUTES, MINUTES);
        }
    }

    public TransactionPointRepository getTransactionPointRepository() {
        return transactionPointDao;
    }

    public SnapshotRepository getSnapshotRepository() {
        return snapshotDao;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public TransactionPointDao getTransactionPointDao() {
        return transactionPointDao;
    }

    public SnapshotDao getSnapshotDao() {
        return snapshotDao;
    }

    public CappedDatabase getCappedDatabase() {
        return cappedDatabase;
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
        if (reaperScheduledRunnable != null) {
            reaperScheduledRunnable.cancel();
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
