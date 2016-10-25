/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.agent.fat.storage.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.fat.storage.util.ImmutableColumn;
import org.glowroot.agent.fat.storage.util.ImmutableIndex;
import org.glowroot.agent.fat.storage.util.Schemas.Column;
import org.glowroot.agent.fat.storage.util.Schemas.ColumnType;
import org.glowroot.agent.fat.storage.util.Schemas.Index;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

class TraceAttributeNameDao {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
            ImmutableColumn.of("trace_attribute_name", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    private static final ImmutableList<Index> indexes = ImmutableList.<Index>of(
            ImmutableIndex.of("trace_attribute_name_idx",
                    ImmutableList.of("transaction_type", "trace_attribute_name")));

    private final DataSource dataSource;

    private final Cache<TraceAttributeNameKey, Boolean> lastCaptureTimeUpdatedInThePastDay =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(1, DAYS)
                    .maximumSize(10000)
                    .build();

    private final Object lock = new Object();

    TraceAttributeNameDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("trace_attribute_name", columns);
        dataSource.syncIndexes("trace_attribute_name", indexes);
    }

    public List<String> readTraceAttributeNames(String transactionType) throws Exception {
        return dataSource.query(new TraceAttributeQuery(transactionType));
    }

    void updateLastCaptureTime(String transactionType, String traceAttributeName, long captureTime)
            throws Exception {
        TraceAttributeNameKey key =
                ImmutableTraceAttributeNameKey.of(transactionType, traceAttributeName);
        if (lastCaptureTimeUpdatedInThePastDay.getIfPresent(key) != null) {
            return;
        }
        synchronized (lock) {
            int updateCount = dataSource.update(
                    "update trace_attribute_name set last_capture_time = ?"
                            + " where transaction_type = ? and trace_attribute_name = ?",
                    captureTime, transactionType, traceAttributeName);
            if (updateCount == 0) {
                dataSource.update(
                        "insert into trace_attribute_name (transaction_type, trace_attribute_name,"
                                + " last_capture_time) values (?, ?, ?)",
                        transactionType, traceAttributeName, captureTime);
            }
        }
        lastCaptureTimeUpdatedInThePastDay.put(key, true);
    }

    void deleteBefore(long captureTime) throws Exception {
        synchronized (lock) {
            // subtracting 1 day to account for rate limiting of updates
            dataSource.update("delete from trace_attribute_name where last_capture_time < ?",
                    captureTime - DAYS.toMillis(1));
        }
    }

    private static class TraceAttributeQuery implements JdbcRowQuery<String> {

        private final String transactionType;

        private TraceAttributeQuery(String transactionType) {
            this.transactionType = transactionType;
        }

        @Override
        public @Untainted String getSql() {
            return "select trace_attribute_name from trace_attribute_name where"
                    + " transaction_type = ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setString(1, transactionType);
        }

        @Override
        public String mapRow(ResultSet resultSet) throws SQLException {
            return checkNotNull(resultSet.getString(1));
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TraceAttributeNameKey {
        String transactionType();
        String traceAttributeName();
    }
}
