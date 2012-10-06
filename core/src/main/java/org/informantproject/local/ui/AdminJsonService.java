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

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.util.DataSource;
import org.informantproject.local.log.LogMessage;
import org.informantproject.local.log.LogMessageDao;
import org.informantproject.local.trace.TraceSinkLocal;
import org.informantproject.local.trace.TraceSnapshotDao;

import com.google.common.io.CharStreams;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to clear captured data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class AdminJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(AdminJsonService.class);

    private final LogMessageDao logMessageDao;
    private final TraceSnapshotDao traceSnapshotDao;
    private final TraceSinkLocal traceSinkLocal;
    private final DataSource dataSource;

    @Inject
    AdminJsonService(LogMessageDao logMessageDao, TraceSnapshotDao traceSnapshotDao,
            TraceSinkLocal traceSinkLocal, DataSource dataSource) {

        this.logMessageDao = logMessageDao;
        this.traceSnapshotDao = traceSnapshotDao;
        this.traceSinkLocal = traceSinkLocal;
        this.dataSource = dataSource;
    }

    @JsonServiceMethod
    void compact() {
        logger.debug("compact()");
        try {
            dataSource.compact();
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @JsonServiceMethod
    void truncate() {
        logger.debug("truncate()");
        traceSnapshotDao.deleteAllSnapshots();
        compact();
    }

    @JsonServiceMethod
    String getLog() {
        logger.debug("getLog()");
        List<LogMessage> logMessages = logMessageDao.readLogMessages();
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        try {
            jw.beginArray();
            for (LogMessage logMessage : logMessages) {
                jw.beginObject();
                jw.name("timestamp");
                jw.value(logMessage.getTimestamp());
                jw.name("level");
                jw.value(logMessage.getLevel().name());
                jw.name("text");
                jw.value(logMessage.getText());
                jw.flush();
                sb.append(",\"exception\":");
                sb.append(logMessage.getException());
                jw.endObject();
            }
            jw.endArray();
            jw.close();
        } catch (IOException e) {
            // this isn't really possible since writing to StringBuilder
            logger.error(e.getMessage(), e);
            return null;
        }
        return sb.toString();
    }

    @JsonServiceMethod
    String getNumPendingTraceWrites() {
        logger.debug("handleNumPendingTraceWrites()");
        return Integer.toString(traceSinkLocal.getPendingCount());
    }
}
