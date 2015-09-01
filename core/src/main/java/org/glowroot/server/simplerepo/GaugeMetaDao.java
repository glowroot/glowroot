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
package org.glowroot.server.simplerepo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;

import org.glowroot.server.simplerepo.util.DataSource;
import org.glowroot.server.simplerepo.util.DataSource.RowMapper;
import org.glowroot.server.simplerepo.util.ImmutableColumn;
import org.glowroot.server.simplerepo.util.ImmutableIndex;
import org.glowroot.server.simplerepo.util.Schemas.Column;
import org.glowroot.server.simplerepo.util.Schemas.Index;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.HOURS;

class GaugeMetaDao {

    private static final ImmutableList<Column> gaugeColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("gauge_id", Types.BIGINT).withIdentity(true),
            ImmutableColumn.of("gauge_name", Types.VARCHAR));

    private static final ImmutableList<Index> gaugeIndexes = ImmutableList
            .<Index>of(ImmutableIndex.of("gauge_meta_idx", ImmutableList.of("gauge_name")));

    // expire after 1 hour to avoid retaining deleted gauge configs indefinitely
    private final Cache<String, Long> gaugeIds =
            CacheBuilder.newBuilder().expireAfterAccess(1, HOURS).build();

    private final DataSource dataSource;

    private final Object lock = new Object();

    GaugeMetaDao(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dataSource.syncTable("gauge_meta", gaugeColumns);
        dataSource.syncIndexes("gauge_meta", gaugeIndexes);
    }

    long getOrCreateGaugeId(String gaugeName) throws Exception {
        synchronized (lock) {
            Long gaugeId = gaugeIds.getIfPresent(gaugeName);
            if (gaugeId != null) {
                return gaugeId;
            }
            gaugeId = readGaugeId(gaugeName);
            if (gaugeId != null) {
                return gaugeId;
            }
            dataSource.update("insert into gauge_meta (gauge_name) values (?)", gaugeName);
            gaugeId = readGaugeId(gaugeName);
            if (gaugeId == null) {
                // it's only possible for this to occur if the data source closing flag has just
                // been set which causes the readGaugeId() to read empty list and return null
                return -1;
            }
            gaugeIds.put(gaugeName, gaugeId);
            return gaugeId;
        }
    }

    @Nullable
    Long getGaugeId(String gaugeName) throws Exception {
        Long gaugeId = gaugeIds.getIfPresent(gaugeName);
        if (gaugeId != null) {
            return gaugeId;
        }
        gaugeId = readGaugeId(gaugeName);
        if (gaugeId == null) {
            return null;
        }
        gaugeIds.put(gaugeName, gaugeId);
        return gaugeId;
    }

    private @Nullable Long readGaugeId(String gaugeName) throws Exception {
        List<Long> gaugeIds =
                dataSource.query("select gauge_id from gauge_meta where gauge_name = ?",
                        new GaugeIdRowMapper(), gaugeName);
        if (gaugeIds.isEmpty()) {
            return null;
        }
        checkState(gaugeIds.size() == 1);
        return gaugeIds.get(0);
    }

    private static class GaugeIdRowMapper implements RowMapper<Long> {
        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }
}
