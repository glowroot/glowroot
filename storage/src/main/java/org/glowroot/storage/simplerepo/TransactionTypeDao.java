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

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.glowroot.storage.repo.TransactionTypeRepository;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.Schemas.Column;
import org.glowroot.storage.simplerepo.util.Schemas.ColumnType;

class TransactionTypeDao implements TransactionTypeRepository {

    private static final String SERVER_ID = "";

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    private final DataSource dataSource;

    private final Object lock = new Object();

    TransactionTypeDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("transaction_types", columns);
    }

    @Override
    public Map<String, List<String>> readTransactionTypes() throws Exception {
        List<String> transactionTypes = dataSource.queryForStringList(
                "select transaction_type from transaction_types order by transaction_type");
        if (transactionTypes == null) {
            // data source is closing
            return ImmutableMap.<String, List<String>>of(SERVER_ID, ImmutableList.<String>of());
        }
        return ImmutableMap.of(SERVER_ID, transactionTypes);
    }

    void updateLastCaptureTime(String transactionType, long captureTime) throws Exception {
        synchronized (lock) {
            int updateCount = dataSource.update("update transaction_types set last_capture_time = ?"
                    + " where transaction_type = ?", captureTime, transactionType);
            if (updateCount == 0) {
                dataSource.update("insert into transaction_types (transaction_type,"
                        + " last_capture_time) values (?, ?)", transactionType, captureTime);
            }
        }
    }

    void deleteAll() throws Exception {
        dataSource.update("truncate table transaction_types");
    }

    void deleteBefore(long captureTime) throws Exception {
        dataSource.update("delete from transaction_types where last_capture_time < ?", captureTime);
    }
}
