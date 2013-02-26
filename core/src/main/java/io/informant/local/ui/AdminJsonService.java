/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.ui;

import io.informant.config.ConfigService;
import io.informant.core.TraceRegistry;
import io.informant.local.store.DataSource;
import io.informant.local.store.LocalTraceSink;
import io.informant.local.store.TraceSnapshotDao;
import io.informant.util.OnlyUsedByTests;
import io.informant.util.Singleton;

import java.io.IOException;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;
import com.google.gson.JsonSyntaxException;

/**
 * Json service for various admin tasks.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class AdminJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(AdminJsonService.class);

    private final TraceSnapshotDao traceSnapshotDao;
    private final ConfigService configService;
    private final LocalTraceSink traceSinkLocal;
    private final DataSource dataSource;
    private final TraceRegistry traceRegistry;

    AdminJsonService(TraceSnapshotDao traceSnapshotDao, ConfigService configService,
            LocalTraceSink traceSinkLocal, DataSource dataSource, TraceRegistry traceRegistry) {
        this.traceSnapshotDao = traceSnapshotDao;
        this.configService = configService;
        this.traceSinkLocal = traceSinkLocal;
        this.dataSource = dataSource;
        this.traceRegistry = traceRegistry;
    }

    @JsonServiceMethod
    void compactData() {
        logger.debug("compactData()");
        try {
            dataSource.compact();
        } catch (SQLException e) {
            // this might be serious, worth logging as error
            logger.error(e.getMessage(), e);
        }
    }

    @JsonServiceMethod
    void deleteAllData() {
        logger.debug("deleteAllData()");
        traceSnapshotDao.deleteAllSnapshots();
    }

    @OnlyUsedByTests
    @JsonServiceMethod
    void resetAllConfig() throws IOException, JsonSyntaxException {
        logger.debug("resetAllConfig()");
        configService.resetAllConfig();
    }

    @OnlyUsedByTests
    @JsonServiceMethod
    String getNumPendingCompleteTraces() {
        logger.debug("getNumPendingCompleteTraces()");
        return Integer.toString(traceSinkLocal.getPendingCompleteTraces().size());
    }

    @OnlyUsedByTests
    @JsonServiceMethod
    String getNumStoredTraceSnapshots() {
        logger.debug("getNumStoredTraceSnapshots()");
        return Long.toString(traceSnapshotDao.count());
    }

    @OnlyUsedByTests
    @JsonServiceMethod
    String getNumActiveTraces() {
        logger.debug("getNumActiveTraces()");
        return Integer.toString(Iterables.size(traceRegistry.getTraces()));
    }
}
