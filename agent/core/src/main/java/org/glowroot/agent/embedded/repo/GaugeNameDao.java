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
import java.sql.SQLException;
import java.util.Set;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcQuery;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.ImmutableIndex;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.agent.embedded.util.Schemas.Index;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

class GaugeNameDao {

    static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("capture_time", ColumnType.BIGINT),
            ImmutableColumn.of("gauge_name", ColumnType.VARCHAR));

    static final ImmutableList<Index> indexes = ImmutableList.<Index>of(
            ImmutableIndex.of("gauge_idx", ImmutableList.of("capture_time", "gauge_name")));

    private final DataSource dataSource;

    private final Cache<GaugeNameRow, Boolean> rowInsertedInThePastDay =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(1, DAYS)
                    .maximumSize(10000)
                    .build();

    private final Object lock = new Object();

    GaugeNameDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("gauge_name", columns);
        dataSource.syncIndexes("gauge_name", indexes);
    }

    void insert(long captureTime, String gaugeName) throws SQLException {
        long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, DAYS.toMillis(1));
        GaugeNameRow key = ImmutableGaugeNameRow.of(rollupCaptureTime, gaugeName);
        if (rowInsertedInThePastDay.getIfPresent(key) != null) {
            return;
        }
        synchronized (lock) {
            dataSource.update("merge into gauge_name (capture_time, gauge_name) key (capture_time,"
                    + " gauge_name) values (?, ?)", rollupCaptureTime, gaugeName);
        }
        rowInsertedInThePastDay.put(key, true);
    }

    Set<String> readAllGaugeNames(long from, long to) throws Exception {
        return dataSource.query(new GaugeNameQuery(from, to));
    }

    void deleteBefore(long captureTime) throws Exception {
        dataSource.deleteBeforeUsingLock("gauge_name", "capture_time", captureTime, lock);
    }

    void invalidateCache() {
        rowInsertedInThePastDay.invalidateAll();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface GaugeNameRow {
        long captureTime();
        String gaugeName();
    }

    private static class GaugeNameQuery implements JdbcQuery<Set<String>> {

        private final long from;
        private final long to;

        private GaugeNameQuery(long from, long to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public @Untainted String getSql() {
            return "select gauge_name from gauge_name where capture_time >= ?"
                    + " and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            long rolledUpFrom = Utils.getRollupCaptureTime(from, DAYS.toMillis(1));
            long rolledUpTo = Utils.getRollupCaptureTime(to, DAYS.toMillis(1));
            preparedStatement.setLong(1, rolledUpFrom);
            preparedStatement.setLong(2, rolledUpTo);
        }

        @Override
        public Set<String> processResultSet(ResultSet resultSet) throws Exception {
            Set<String> gaugeNames = Sets.newHashSet();
            while (resultSet.next()) {
                gaugeNames.add(checkNotNull(resultSet.getString(1)));
            }
            return gaugeNames;
        }

        @Override
        public Set<String> valueIfDataSourceClosed() {
            return ImmutableSet.of();
        }
    }
}
