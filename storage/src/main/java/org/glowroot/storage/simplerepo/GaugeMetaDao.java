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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
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
import static java.util.concurrent.TimeUnit.HOURS;

class GaugeMetaDao {

    private static final ImmutableList<Column> gaugeColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("gauge_id", ColumnType.AUTO_IDENTITY),
            ImmutableColumn.of("server_group", ColumnType.VARCHAR),
            ImmutableColumn.of("gauge_name", ColumnType.VARCHAR));

    private static final ImmutableList<Index> gaugeIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("gauge_meta_idx", ImmutableList.of("server_group", "gauge_name")));

    // expire after 1 hour to avoid retaining deleted gauge configs indefinitely
    private final Cache<GaugeKey, Long> gaugeIds =
            CacheBuilder.newBuilder().expireAfterAccess(1, HOURS).build();

    private final DataSource dataSource;

    private final Object lock = new Object();

    GaugeMetaDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        Schema schema = dataSource.getSchema();
        schema.syncTable("gauge_meta", gaugeColumns);
        schema.syncIndexes("gauge_meta", gaugeIndexes);
    }

    long getOrCreateGaugeId(String serverGroup, String gaugeName) throws Exception {
        GaugeKey gaugeKey = ImmutableGaugeKey.of(serverGroup, gaugeName);
        synchronized (lock) {
            Long gaugeId = gaugeIds.getIfPresent(gaugeKey);
            if (gaugeId != null) {
                return gaugeId;
            }
            gaugeId = readGaugeId(gaugeKey);
            if (gaugeId != null) {
                return gaugeId;
            }
            dataSource.update("insert into gauge_meta (server_group, gauge_name) values (?, ?)",
                    serverGroup, gaugeName);
            gaugeId = readGaugeId(gaugeKey);
            if (gaugeId == null) {
                // it's only possible for this to occur if the data source closing flag has just
                // been set which causes the readGaugeId() to read empty list and return null
                return -1;
            }
            gaugeIds.put(gaugeKey, gaugeId);
            return gaugeId;
        }
    }

    @Nullable
    Long getGaugeId(String serverGroup, String gaugeName) throws Exception {
        GaugeKey gaugeKey = ImmutableGaugeKey.of(serverGroup, gaugeName);
        Long gaugeId = gaugeIds.getIfPresent(gaugeKey);
        if (gaugeId != null) {
            return gaugeId;
        }
        gaugeId = readGaugeId(gaugeKey);
        if (gaugeId == null) {
            return null;
        }
        gaugeIds.put(gaugeKey, gaugeId);
        return gaugeId;
    }

    List<String> readAllGaugeNames() throws Exception {
        return dataSource.query("select gauge_name from gauge_meta", new RowMapper<String>() {
            @Override
            public String mapRow(ResultSet resultSet) throws Exception {
                return checkNotNull(resultSet.getString(1));
            }

        });
    }

    void deleteAll(String serverGroup) throws Exception {
        synchronized (lock) {
            dataSource.deleteAll("gauge_meta", "server_group", serverGroup);
            gaugeIds.invalidateAll();
        }
    }

    private @Nullable Long readGaugeId(GaugeKey gaugeKey) throws Exception {
        List<Long> gaugeIds = dataSource.query(
                "select gauge_id from gauge_meta where server_group = ? and gauge_name = ?",
                new GaugeIdRowMapper(), gaugeKey.serverGroup(), gaugeKey.gaugeName());
        if (gaugeIds.isEmpty()) {
            return null;
        }
        checkState(gaugeIds.size() == 1);
        return gaugeIds.get(0);
    }

    @Value.Immutable
    @Styles.AllParameters
    interface GaugeKey {
        String serverGroup();
        String gaugeName();
    }

    private static class GaugeIdRowMapper implements RowMapper<Long> {
        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }
}
