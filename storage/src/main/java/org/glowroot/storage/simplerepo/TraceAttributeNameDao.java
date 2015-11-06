/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.storage.simplerepo;

import java.sql.SQLException;
import java.util.List;

import com.google.common.collect.ImmutableList;

import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.storage.simplerepo.util.Schema.Column;
import org.glowroot.storage.simplerepo.util.Schema.ColumnType;
import org.glowroot.storage.simplerepo.util.Schema.Index;

class TraceAttributeNameDao {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("server_id", ColumnType.VARCHAR),
            ImmutableColumn.of("trace_attribute_name", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    // important for this to be unique index to prevent race condition in clustered central
    private static final ImmutableList<Index> indexes = ImmutableList.<Index>of(
            ImmutableIndex.builder()
                    .name("trace_attribute_names_idx")
                    .addColumns("server_id", "trace_attribute_name")
                    .unique(true)
                    .build());

    private final DataSource dataSource;

    TraceAttributeNameDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        Schema schema = dataSource.getSchema();
        schema.syncTable("trace_attribute_names", columns);
        schema.syncIndexes("trace_attribute_names", indexes);
    }

    public List<String> readTraceAttributeNames(String serverRollup) throws Exception {
        // FIXME maintain table of server_id/server_rollup associations and join that here
        // (maintain this table on agent "Hello", wipe out prior associations and add new ones)
        return dataSource.queryForStringList(
                "select trace_attribute_name from trace_attribute_names where server_id = ?",
                serverRollup);
    }

    void updateLastCaptureTime(String serverId, String traceAttributeName, long captureTime)
            throws Exception {
        int updateCount = dataSource.update(
                "update trace_attribute_names set last_capture_time = ? where server_id = ?"
                        + " and trace_attribute_name = ?",
                captureTime, serverId, traceAttributeName);
        if (updateCount == 1) {
            return;
        }
        try {
            dataSource.update(
                    "insert into trace_attribute_names (server_id, trace_attribute_name,"
                            + " last_capture_time) values (?, ?, ?)",
                    serverId, traceAttributeName, captureTime);
        } catch (SQLException e) {
            if (dataSource.queryForExists(
                    "select 1 from trace_attribute_names where server_id = ?"
                            + " and trace_attribute_name = ?",
                    serverId, traceAttributeName)) {
                // unique constraint violation above, race condition in central cluster, ok
                return;
            }
            throw e;
        }
    }

    void deleteAll(String serverId) throws Exception {
        dataSource.update("delete from trace_attribute_names where server_id = ?", serverId);
    }

    void deleteBefore(String serverId, long captureTime) throws Exception {
        dataSource.update("delete from trace_attribute_names where server_id = ?"
                + " and last_capture_time < ?", serverId, captureTime);
    }
}
