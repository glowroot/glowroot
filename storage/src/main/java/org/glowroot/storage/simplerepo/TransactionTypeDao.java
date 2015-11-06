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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.glowroot.storage.repo.TransactionTypeRepository;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.ResultSetExtractor;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.storage.simplerepo.util.Schema.Column;
import org.glowroot.storage.simplerepo.util.Schema.ColumnType;
import org.glowroot.storage.simplerepo.util.Schema.Index;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionTypeDao implements TransactionTypeRepository {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("server_rollup", ColumnType.VARCHAR),
            ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    // important for this to be unique index to prevent race condition in clustered central
    private static final ImmutableList<Index> indexes = ImmutableList.<Index>of(
            ImmutableIndex.builder()
                    .name("transaction_types_idx")
                    .addColumns("server_rollup", "transaction_type")
                    .unique(true)
                    .build());

    private final DataSource dataSource;

    TransactionTypeDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        Schema schema = dataSource.getSchema();
        schema.syncTable("transaction_types", columns);
        schema.syncIndexes("transaction_types", indexes);
    }

    @Override
    public Map<String, List<String>> readTransactionTypes() throws Exception {
        Map<String, List<String>> transactionTypes =
                dataSource.query("select server_rollup, transaction_type from transaction_types"
                        + " order by server_rollup, transaction_type",
                        new TransactionTypeResultSetExtractor());
        if (transactionTypes == null) {
            // data source is closing
            return ImmutableMap.of();
        }
        return transactionTypes;
    }

    void updateLastCaptureTime(String serverRollup, String transactionType, long captureTime)
            throws Exception {
        int updateCount = dataSource.update("update transaction_types set last_capture_time = ?"
                + " where server_rollup = ? and transaction_type = ?", captureTime, serverRollup,
                transactionType);
        if (updateCount == 1) {
            return;
        }
        try {
            dataSource.update("insert into transaction_types (server_rollup, transaction_type,"
                    + "last_capture_time) values (?, ?, ?)", serverRollup, transactionType,
                    captureTime);
        } catch (SQLException e) {
            if (dataSource.queryForExists("select 1 from transaction_types where server_rollup = ?"
                    + " and transaction_type = ?", serverRollup, transactionType)) {
                // unique constraint violation above, race condition in central cluster, ok
                return;
            }
            throw e;
        }
    }

    void deleteAll(String serverRollup) throws Exception {
        dataSource.update("delete from transaction_types where server_rollup = ?", serverRollup);
    }

    void deleteBefore(String serverRollup, long captureTime) throws Exception {
        dataSource.update("delete from transaction_types where server_rollup = ?"
                + " and last_capture_time < ?", serverRollup, captureTime);
    }

    private static class TransactionTypeResultSetExtractor
            implements ResultSetExtractor<Map<String, List<String>>> {
        @Override
        public Map<String, List<String>> extractData(ResultSet resultSet) throws SQLException {
            ImmutableMap.Builder<String, List<String>> builder = ImmutableMap.builder();
            String currServerRollup = null;
            List<String> currTransactionTypes = Lists.newArrayList();
            while (resultSet.next()) {
                String serverRollup = checkNotNull(resultSet.getString(1));
                String transactionType = checkNotNull(resultSet.getString(2));
                if (currServerRollup == null) {
                    currServerRollup = serverRollup;
                }
                if (!serverRollup.equals(currServerRollup)) {
                    builder.put(currServerRollup, ImmutableList.copyOf(currTransactionTypes));
                    currServerRollup = serverRollup;
                    currTransactionTypes = Lists.newArrayList();
                }
                currTransactionTypes.add(transactionType);
            }
            if (currServerRollup != null) {
                builder.put(currServerRollup, ImmutableList.copyOf(currTransactionTypes));
            }
            return builder.build();
        }
    }
}
