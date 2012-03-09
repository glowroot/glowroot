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
package org.informantproject.local.trace;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSource.BatchPreparedStatementSetter;
import org.informantproject.core.util.DataSource.Column;
import org.informantproject.core.util.DataSource.PrimaryKeyColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Data access object for storing and reading stack traces from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class StackTraceDao {

    private static final Logger logger = LoggerFactory.getLogger(StackTraceDao.class);

    private static ImmutableList<Column> columns = ImmutableList.of(
            new PrimaryKeyColumn("hash", Types.VARCHAR),
            new Column("stack_trace", Types.CLOB));

    private final DataSource dataSource;

    private final boolean valid;

    // cache only contains hashes which are 20 bytes each, seems ok to keep 5000
    // TODO expose cache efficiency via stats
    private final Cache<String, Boolean> storedHashes = CacheBuilder.newBuilder().maximumSize(5000)
            .build();

    @Inject
    StackTraceDao(DataSource dataSource) {
        this.dataSource = dataSource;
        boolean errorOnInit = false;
        try {
            if (!dataSource.tableExists("stack_trace")) {
                dataSource.createTable("stack_trace", columns);
            } else if (dataSource.tableNeedsUpgrade("stack_trace", columns)) {
                logger.warn("upgrading stack_trace table schema, which unfortunately at this point"
                        + " just means dropping and re-create the table (losing existing data)");
                dataSource.execute("drop table stack_trace");
                dataSource.createTable("stack_trace", columns);
                logger.warn("the schema for the stack_trace table was outdated so it was dropped"
                        + " and re-created, existing stack_trace data was lost");
            }
        } catch (SQLException e) {
            errorOnInit = true;
            logger.error(e.getMessage(), e);
        }
        this.valid = !errorOnInit;
    }

    public void storeStackTraces(Map<String, String> stackTraces) {
        logger.debug("storeStackTraces()");
        if (!valid) {
            return;
        }
        Map<String, String> newStackTraces = Maps.filterKeys(stackTraces, new Predicate<String>() {
            public boolean apply(String hash) {
                return storedHashes.getIfPresent(hash) == null;
            }
        });
        final List<Entry<String, String>> newEntries = Lists.newArrayList(newStackTraces
                .entrySet());
        try {
            dataSource.batchUpdate("merge into stack_trace (hash, stack_trace) values (?, ?)",
                    new BatchPreparedStatementSetter() {
                        public void setValues(PreparedStatement preparedStatement, int i)
                                throws SQLException {
                            preparedStatement.setString(1, newEntries.get(i).getKey());
                            preparedStatement.setString(2, newEntries.get(i).getValue());
                        }
                        public int getBatchSize() {
                            return newEntries.size();
                        }
                    });
            for (String hash : newStackTraces.keySet()) {
                storedHashes.put(hash, Boolean.TRUE);
            }
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return;
        }
    }

    public String readStackTrace(String hash) {
        logger.debug("readStackTrace(): hash={}", hash);
        if (!valid) {
            return null;
        }
        try {
            return dataSource.queryForString("select stack_trace from stack_trace where hash = ?",
                    hash);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

}
