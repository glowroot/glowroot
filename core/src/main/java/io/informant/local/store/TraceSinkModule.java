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
import io.informant.util.Clock;
import io.informant.util.ThreadSafe;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class TraceSinkModule {

    private static final Logger logger = LoggerFactory.getLogger(TraceSinkModule.class);

    private final RollingFile rollingFile;
    private final TraceSnapshotService traceSnapshotService;
    private final TraceSnapshotDao traceSnapshotDao;
    private final LocalTraceSink traceSink;
    private final TraceSnapshotReaper traceSnapshotReaper;

    public TraceSinkModule(ConfigModule configModule, DataSourceModule dataSourceModule)
            throws Exception {
        Ticker ticker = configModule.getTicker();
        Clock clock = configModule.getClock();
        File dataDir = configModule.getDataDir();
        ConfigService configService = configModule.getConfigService();
        int rollingSizeMb = configService.getGeneralConfig().getRollingSizeMb();
        DataSource dataSource = dataSourceModule.getDataSource();
        rollingFile = new RollingFile(new File(dataDir, "informant.rolling.db"),
                rollingSizeMb * 1024);
        traceSnapshotService = new TraceSnapshotService(configService);
        traceSnapshotDao = new TraceSnapshotDao(dataSource, rollingFile, clock);
        traceSink = new LocalTraceSink(traceSnapshotService, traceSnapshotDao, ticker);
        traceSnapshotReaper = new TraceSnapshotReaper(configService, traceSnapshotDao, clock);
    }

    public void close() {
        logger.debug("close()");
        traceSnapshotReaper.close();
        traceSink.close();
        try {
            rollingFile.close();
        } catch (IOException e) {
            // warning only since it occurs during shutdown anyways
            logger.warn(e.getMessage(), e);
        }
    }

    public RollingFile getRollingFile() {
        return rollingFile;
    }

    public TraceSnapshotService getTraceSnapshotService() {
        return traceSnapshotService;
    }

    public TraceSnapshotDao getTraceSnapshotDao() {
        return traceSnapshotDao;
    }

    public LocalTraceSink getTraceSink() {
        return traceSink;
    }
}
