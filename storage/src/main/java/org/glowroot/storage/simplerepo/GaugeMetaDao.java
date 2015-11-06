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

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.RowMapper;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.storage.simplerepo.util.Schema.Column;
import org.glowroot.storage.simplerepo.util.Schema.ColumnType;
import org.glowroot.storage.simplerepo.util.Schema.Index;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

class GaugeMetaDao {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("gauge_id", ColumnType.AUTO_IDENTITY),
            ImmutableColumn.of("server_rollup", ColumnType.VARCHAR),
            ImmutableColumn.of("gauge_name", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    // important for this to be unique index to prevent race condition in clustered central
    private static final ImmutableList<Index> indexes = ImmutableList.<Index>of(
            ImmutableIndex.builder()
                    .name("gauge_meta_idx")
                    .addColumns("server_rollup", "gauge_name")
                    .unique(true)
                    .build());

    private final DataSource dataSource;

    GaugeMetaDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        Schema schema = dataSource.getSchema();
        schema.syncTable("gauge_meta", columns);
        schema.syncIndexes("gauge_meta", indexes);
    }

    long updateLastCaptureTime(String serverRollup, String gaugeName, long captureTime)
            throws Exception {
        Long gaugeId = getGaugeId(serverRollup, gaugeName);
        if (gaugeId != null) {
            dataSource.update("update gauge_meta set last_capture_time = ? where server_rollup = ?"
                    + " and gauge_name = ?", captureTime, serverRollup, gaugeName);
            return gaugeId;
        }
        try {
            dataSource.update("insert into gauge_meta (server_rollup, gauge_name,"
                    + "last_capture_time) values (?, ?, ?)", serverRollup, gaugeName,
                    captureTime);
        } catch (SQLException e) {
            gaugeId = getGaugeId(serverRollup, gaugeName);
            if (gaugeId != null) {
                // unique constraint violation above, race condition in central cluster, ok
                return gaugeId;
            }
            throw e;
        }
        gaugeId = getGaugeId(serverRollup, gaugeName);
        if (gaugeId == null) {
            // data source closing --or--
            // deleteAll() was called after insert and before select above
            return -1;
        }
        return gaugeId;
    }

    @Nullable
    Long getGaugeId(String serverRollup, String gaugeName) throws Exception {
        List<Long> gaugeIds = dataSource.query(
                "select gauge_id from gauge_meta where server_rollup = ? and gauge_name = ?",
                new GaugeIdRowMapper(), serverRollup, gaugeName);
        if (gaugeIds.isEmpty()) {
            return null;
        }
        checkState(gaugeIds.size() == 1);
        return gaugeIds.get(0);
    }

    List<String> readAllGaugeNames(String serverRollup) throws Exception {
        return dataSource.query("select gauge_name from gauge_meta where server_rollup = ?",
                new GaugeNameRowMapper(), serverRollup);
    }

    void deleteAll(String serverRollup) throws Exception {
        dataSource.update("delete from gauge_meta where server_rollup = ?", serverRollup);
    }

    void deleteBefore(String serverRollup, long captureTime) throws Exception {
        dataSource.update("delete from gauge_meta where server_rollup = ?"
                + " and last_capture_time < ?", serverRollup, captureTime);
    }

    @Value.Immutable
    @Styles.AllParameters
    interface GaugeKey {
        String serverRollup();
        String gaugeName();
    }

    private static class GaugeIdRowMapper implements RowMapper<Long> {
        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }

    private static class GaugeNameRowMapper implements RowMapper<String> {
        @Override
        public String mapRow(ResultSet resultSet) throws SQLException {
            return checkNotNull(resultSet.getString(1));
        }
    }
}
