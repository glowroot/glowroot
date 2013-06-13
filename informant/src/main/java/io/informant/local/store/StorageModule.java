/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.local.store;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import checkers.igj.quals.ReadOnly;
import com.google.common.base.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.collector.AggregateRepository;
import io.informant.collector.SnapshotRepository;
import io.informant.common.Clock;
import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class StorageModule {

    private static final Logger logger = LoggerFactory.getLogger(StorageModule.class);

    private final DataSource dataSource;
    private final RollingFile rollingFile;
    private final SnapshotDao snapshotDao;
    private final AggregateDao aggregateDao;

    public StorageModule(File dataDir, @ReadOnly Map<String, String> properties, Ticker ticker,
            Clock clock, ConfigModule configModule, ScheduledExecutorService scheduledExecutor)
            throws Exception {
        // mem db is only used for testing (by informant-test-container)
        String h2MemDb = properties.get("internal.h2.memdb");
        if (Boolean.parseBoolean(h2MemDb)) {
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "informant.h2.db"));
        }
        ConfigService configService = configModule.getConfigService();
        int rollingSizeMb = configService.getStorageConfig().getRollingSizeMb();
        rollingFile = new RollingFile(new File(dataDir, "informant.rolling.db"),
                rollingSizeMb * 1024, scheduledExecutor, ticker);
        snapshotDao = new SnapshotDao(dataSource, rollingFile);
        new SnapshotReaper(configService, snapshotDao, clock).start(scheduledExecutor);
        aggregateDao = new AggregateDao(dataSource);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public RollingFile getRollingFile() {
        return rollingFile;
    }

    public SnapshotDao getSnapshotDao() {
        return snapshotDao;
    }

    public SnapshotRepository getSnapshotRepository() {
        return snapshotDao;
    }

    public AggregateRepository getAggregateRepository() {
        return aggregateDao;
    }

    public AggregateDao getAggregateDao() {
        return aggregateDao;
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
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
