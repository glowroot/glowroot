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

import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.core.SnapshotSink;
import io.informant.util.Clock;
import io.informant.util.DaemonExecutors;
import io.informant.util.OnlyUsedByTests;
import io.informant.util.ThreadSafe;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class StorageModule {

    private static final Logger logger = LoggerFactory.getLogger(StorageModule.class);

    private final RollingFile rollingFile;
    private final TraceSnapshotDao traceSnapshotDao;
    private final ScheduledExecutorService scheduledExecutor;

    public StorageModule(ConfigModule configModule, DataSourceModule dataSourceModule)
            throws Exception {
        Ticker ticker = configModule.getTicker();
        Clock clock = configModule.getClock();
        File dataDir = configModule.getDataDir();
        ConfigService configService = configModule.getConfigService();
        int rollingSizeMb = configService.getGeneralConfig().getRollingSizeMb();
        DataSource dataSource = dataSourceModule.getDataSource();

        scheduledExecutor = DaemonExecutors.newSingleThreadScheduledExecutor("Informant-Storage");
        rollingFile = new RollingFile(new File(dataDir, "informant.rolling.db"),
                rollingSizeMb * 1024, scheduledExecutor, ticker);
        traceSnapshotDao = new TraceSnapshotDao(dataSource, rollingFile, clock);
        new TraceSnapshotReaper(configService, traceSnapshotDao, clock).start(scheduledExecutor);
    }

    public RollingFile getRollingFile() {
        return rollingFile;
    }

    public TraceSnapshotDao getTraceSnapshotDao() {
        return traceSnapshotDao;
    }

    public SnapshotSink getSnapshotSink() {
        return traceSnapshotDao;
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
        scheduledExecutor.shutdownNow();
        try {
            rollingFile.close();
        } catch (IOException e) {
            // warning only since it occurs during shutdown anyways
            logger.warn(e.getMessage(), e);
        }
    }
}
