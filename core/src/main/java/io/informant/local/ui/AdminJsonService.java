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

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.config.ConfigService;
import io.informant.core.util.DataSource;
import io.informant.core.util.OnlyUsedByTests;
import io.informant.local.log.LogMessage;
import io.informant.local.log.LogMessageDao;
import io.informant.local.trace.TraceSinkLocal;
import io.informant.local.trace.TraceSnapshotDao;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import com.google.common.io.CharStreams;
import com.google.gson.JsonSyntaxException;
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
    private final ConfigService configService;
    private final TraceSinkLocal traceSinkLocal;
    private final DataSource dataSource;

    @Inject
    AdminJsonService(LogMessageDao logMessageDao, TraceSnapshotDao traceSnapshotDao,
            ConfigService configService, TraceSinkLocal traceSinkLocal, DataSource dataSource) {
        this.logMessageDao = logMessageDao;
        this.traceSnapshotDao = traceSnapshotDao;
        this.configService = configService;
        this.traceSinkLocal = traceSinkLocal;
        this.dataSource = dataSource;
    }

    @JsonServiceMethod
    void compactData() {
        logger.debug("compactData()");
        try {
            dataSource.compact();
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @JsonServiceMethod
    void truncateData() {
        logger.debug("truncateData()");
        traceSnapshotDao.deleteAllSnapshots();
        compactData();
    }

    @OnlyUsedByTests
    @JsonServiceMethod
    void truncateConfig() throws IOException, JsonSyntaxException {
        logger.debug("truncateConfig()");
        configService.deleteConfig();
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
                jw.value(logMessage.getLevel().name().toLowerCase(Locale.ENGLISH));
                jw.name("loggerName");
                jw.value(logMessage.getLoggerName());
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
            return "[]";
        }
        return sb.toString();
    }

    @JsonServiceMethod
    void truncateLog() {
        logger.debug("truncateLog()");
        logMessageDao.deleteAllLogMessages();
    }

    @JsonServiceMethod
    String getNumPendingTraceWrites() {
        logger.debug("getNumPendingTraceWrites()");
        return Integer.toString(traceSinkLocal.getPendingCount());
    }
}
