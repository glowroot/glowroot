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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcQuery;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.ImmutableIndex;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.agent.embedded.util.Schemas.Index;
import org.glowroot.common.repo.TraceAttributeNameRepository;
import org.glowroot.common.util.Styles;

import static java.util.concurrent.TimeUnit.DAYS;

class TraceAttributeNameDao implements TraceAttributeNameRepository {

    private static final String AGENT_ID = "";

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

    @Override
    public Map<String, Map<String, List<String>>> read() throws Exception {
        Map<String, Map<String, List<String>>> traceAttributesNames = Maps.newHashMap();
        traceAttributesNames.put(AGENT_ID, dataSource.query(new TraceAttributeQuery()));
        return traceAttributesNames;
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
        // subtracting 1 day to account for rate limiting of updates
        dataSource.deleteBeforeUsingLock("trace_attribute_name", "last_capture_time",
                captureTime - DAYS.toMillis(1), lock);
    }

    void invalidateCache() {
        lastCaptureTimeUpdatedInThePastDay.invalidateAll();
    }

    private static class TraceAttributeQuery implements JdbcQuery<Map<String, List<String>>> {

        @Override
        public @Untainted String getSql() {
            return "select transaction_type, trace_attribute_name from trace_attribute_name";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) {}

        @Override
        public Map<String, List<String>> processResultSet(ResultSet resultSet) throws Exception {
            ListMultimap<String, String> multimap = ArrayListMultimap.create();
            while (resultSet.next()) {
                multimap.put(resultSet.getString(1), resultSet.getString(2));
            }
            return Multimaps.asMap(multimap);
        }

        @Override
        public Map<String, List<String>> valueIfDataSourceClosed() {
            return ImmutableMap.of();
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TraceAttributeNameKey {
        String transactionType();
        String traceAttributeName();
    }
}
