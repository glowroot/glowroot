/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.util.List;
import java.util.Map;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.common.repo.TransactionTypeRepository;

import static java.util.concurrent.TimeUnit.DAYS;

class TransactionTypeDao implements TransactionTypeRepository {

    private static final String AGENT_ID = "";

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    private final DataSource dataSource;

    private final Cache<String, Boolean> lastCaptureTimeUpdatedInThePastDay =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(1, DAYS)
                    .maximumSize(10000)
                    .build();

    private final Object lock = new Object();

    TransactionTypeDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("transaction_types", columns);
    }

    @Override
    public Map<String, List<String>> read() throws Exception {
        List<String> transactionTypes = dataSource.queryForStringList(
                "select transaction_type from transaction_types order by transaction_type");
        if (transactionTypes == null) {
            // data source is closing
            return ImmutableMap.<String, List<String>>of(AGENT_ID, ImmutableList.<String>of());
        }
        return ImmutableMap.of(AGENT_ID, transactionTypes);
    }

    void updateLastCaptureTime(String transactionType, long captureTime) throws Exception {
        if (lastCaptureTimeUpdatedInThePastDay.getIfPresent(transactionType) != null) {
            return;
        }
        synchronized (lock) {
            int updateCount = dataSource.update("update transaction_types set last_capture_time = ?"
                    + " where transaction_type = ?", captureTime, transactionType);
            if (updateCount == 0) {
                dataSource.update(
                        "insert into transaction_types (transaction_type, last_capture_time)"
                                + " values (?, ?)",
                        transactionType, captureTime);
            }
        }
        lastCaptureTimeUpdatedInThePastDay.put(transactionType, true);
    }

    void deleteBefore(long captureTime) throws Exception {
        // subtracting 1 day to account for rate limiting of updates
        dataSource.deleteBeforeUsingLock("transaction_types", "last_capture_time",
                captureTime - DAYS.toMillis(1), lock);
    }

    void invalidateCache() {
        lastCaptureTimeUpdatedInThePastDay.invalidateAll();
    }
}
