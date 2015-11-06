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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;

import org.glowroot.storage.repo.TransactionTypeRepository;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.ResultSetExtractor;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.storage.simplerepo.util.Schema.Column;
import org.glowroot.storage.simplerepo.util.Schema.ColumnType;
import org.glowroot.storage.simplerepo.util.Schema.Index;

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
    public SortedSetMultimap<String, String> readTransactionTypes() throws Exception {
        SortedSetMultimap<String, String> transactionTypes =
                dataSource.query("select server_rollup, transaction_type from transaction_types",
                        new TransactionTypeResultSetExtractor());
        if (transactionTypes == null) {
            // data source is closing
            return TreeMultimap.create();
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
            implements ResultSetExtractor<SortedSetMultimap<String, String>> {
        @Override
        public SortedSetMultimap<String, String> extractData(ResultSet resultSet)
                throws SQLException {
            SortedSetMultimap<String, String> multimap = TreeMultimap
                    .create(String.CASE_INSENSITIVE_ORDER, String.CASE_INSENSITIVE_ORDER);
            while (resultSet.next()) {
                multimap.put(resultSet.getString(1), resultSet.getString(2));
            }
            return multimap;
        }
    }
}
