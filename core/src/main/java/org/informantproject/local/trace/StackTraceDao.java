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

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSource.Column;
import org.informantproject.core.util.DataSource.PrimaryKeyColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.io.CharStreams;
import com.google.gson.stream.JsonWriter;
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

    private final Map<String, Boolean> storedHashes = new ConcurrentHashMap<String, Boolean>();

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

    String storeStackTrace(StackTraceElement[] stackTraceElements) {
        logger.debug("storeStackTrace()");
        if (!valid) {
            return null;
        }
        String json;
        try {
            json = toJson(stackTraceElements);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        String hex = Hashing.sha1().hashString(json, Charsets.UTF_8).toString();
        if (storedHashes.containsKey(hex)) {
            return hex;
        }
        try {
            // TODO optimize with local cache
            dataSource.update("merge into stack_trace (hash, stack_trace) values (?, ?)", hex,
                    json);
            storedHashes.put(hex, Boolean.TRUE);
            return hex;
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
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

    private static String toJson(StackTraceElement[] stackTraceElements) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        for (StackTraceElement stackTraceElement : stackTraceElements) {
            jw.value(stackTraceElement.toString());
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }
}
