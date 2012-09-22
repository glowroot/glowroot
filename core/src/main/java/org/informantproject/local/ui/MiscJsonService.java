/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.local.ui;

import java.sql.SQLException;

import org.informantproject.core.util.Clock;
import org.informantproject.core.util.DataSource;
import org.informantproject.local.trace.TraceSinkLocal;
import org.informantproject.local.trace.TraceSnapshotDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to clear captured data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class MiscJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(MiscJsonService.class);

    private final TraceSnapshotDao traceSnapshotDao;
    private final TraceSinkLocal traceSinkLocal;
    private final DataSource dataSource;
    private final Clock clock;

    @Inject
    MiscJsonService(TraceSnapshotDao traceSnapshotDao, TraceSinkLocal traceSinkLocal,
            DataSource dataSource, Clock clock) {

        this.traceSnapshotDao = traceSnapshotDao;
        this.traceSinkLocal = traceSinkLocal;
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @JsonServiceMethod
    void clearData(String message) {
        logger.debug("handleCleardata(): message={}", message);
        JsonObject request = new JsonParser().parse(message).getAsJsonObject();
        long keepMillis = request.get("keepMillis").getAsLong();
        boolean compact = request.get("compact").getAsBoolean();
        if (keepMillis == 0) {
            traceSnapshotDao.deleteAllSnapshots();
        } else {
            traceSnapshotDao.deleteSnapshots(0, clock.currentTimeMillis() - keepMillis);
        }
        if (compact) {
            try {
                dataSource.compact();
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @JsonServiceMethod
    String getNumPendingTraceWrites() {
        logger.debug("handleNumPendingTraceWrites()");
        return Integer.toString(traceSinkLocal.getPendingCount());
    }

    @JsonServiceMethod
    String getDbFilePath() {
        return dataSource.getDbFile().getAbsolutePath();
    }
}
