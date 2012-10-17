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
package io.informant.local.log;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.log.Level;
import io.informant.core.util.DataSource;
import io.informant.core.util.DataSource.RowMapper;
import io.informant.core.util.Schemas.Column;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Data access object for storing and reading log messages from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class LogMessageDao {

    private static final Logger logger = LoggerFactory.getLogger(LogMessageDao.class);

    private static final int TABLE_LIMIT = 1000;
    private static final int TABLE_LIMIT_APPLIED_EVERY = 100;

    private static final ImmutableList<Column> columns = ImmutableList.of(
            new Column("timestamp", Types.BIGINT),
            new Column("level", Types.VARCHAR),
            new Column("logger_name", Types.VARCHAR),
            new Column("text", Types.VARCHAR),
            new Column("exception", Types.VARCHAR)); // json data

    private final DataSource dataSource;
    private final boolean valid;

    private final Object counterLock = new Object();
    @GuardedBy("countLock")
    private long counter;

    @Inject
    LogMessageDao(DataSource dataSource) {
        this.dataSource = dataSource;
        boolean localValid;
        try {
            dataSource.syncTable("log_message", columns);
            localValid = true;
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            localValid = false;
        }
        valid = localValid;
    }

    public List<LogMessage> readLogMessages() {
        if (!valid) {
            return ImmutableList.of();
        }
        try {
            return dataSource.query("select timestamp, level, logger_name, text, exception from"
                    + " log_message order by timestamp", new Object[0], new LogMessageRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    public void storeLogMessage(LogMessage logMessage) {
        if (!valid) {
            return;
        }
        try {
            dataSource.update("insert into log_message (timestamp, level, logger_name, text,"
                    + " exception) values (?, ?, ?, ?, ?)", new Object[] {
                    logMessage.getTimestamp(), logMessage.getLevel().name(),
                    logMessage.getLoggerName(), logMessage.getText(), logMessage.getException() });
            synchronized (counterLock) {
                if (counter++ % TABLE_LIMIT_APPLIED_EVERY == 0) {
                    // good that it checks on the first store of each jvm cycle, otherwise could get
                    // large with lots of short jvm cycles
                    long count = count();
                    if (count > TABLE_LIMIT) {
                        long timestamp = dataSource.queryForLong("select timestamp from"
                                + " log_message order by timestamp desc limit 1 offset "
                                + TABLE_LIMIT);
                        dataSource.execute("delete from log_message where timestamp <= "
                                + timestamp);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void deleteAllLogMessages() {
        if (!valid) {
            return;
        }
        try {
            dataSource.execute("truncate table log_message");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @VisibleForTesting
    long count() {
        if (!valid) {
            return 0;
        }
        try {
            return dataSource.queryForLong("select count(*) from log_message");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return 0;
        }
    }

    @ThreadSafe
    private static class LogMessageRowMapper implements RowMapper<LogMessage> {

        public LogMessage mapRow(ResultSet resultSet) throws SQLException {
            long timestamp = resultSet.getLong(1);
            String levelText = resultSet.getString(2);
            String loggerName = resultSet.getString(3);
            String text = resultSet.getString(4);
            String exception = resultSet.getString(5);
            Level level;
            try {
                level = Level.valueOf(levelText);
            } catch (IllegalArgumentException e) {
                logger.warn("unexpected level '{}' in log_message table", levelText);
                level = Level.ERROR;
                text += " [marked as error because unexpected level '" + levelText
                        + "' found in h2 database]";
            }
            return LogMessage.from(timestamp, level, loggerName, text, exception);
        }
    }
}
