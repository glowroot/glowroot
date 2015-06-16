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
package org.glowroot.local.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.local.store.DataSource.RowMapper;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.HOURS;

class GaugeMetaDao {

    private static final ImmutableList<Column> gaugeColumns = ImmutableList.<Column>of(
            Column.of("id", Types.BIGINT).withIdentity(true),
            Column.of("name", Types.VARCHAR),
            Column.of("ever_increasing", Types.BOOLEAN)); // used by gauge point rollup

    private static final ImmutableList<Index> gaugeIndexes =
            ImmutableList.<Index>of(Index.of("gauge_meta_idx", ImmutableList.of("name")));

    // expire after 1 hour to avoid retaining deleted gauge configs indefinitely
    private final Cache<String, GaugeMeta> gaugeMetas =
            CacheBuilder.newBuilder().expireAfterAccess(1, HOURS).build();

    private final DataSource dataSource;

    private final Object lock = new Object();

    GaugeMetaDao(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        // upgrade from 0.8 to 0.8.1
        dataSource.renameTable("gauge", "gauge_meta");
        dataSource.syncTable("gauge_meta", gaugeColumns);
        dataSource.syncIndexes("gauge_meta", gaugeIndexes);
    }

    long getOrCreateGaugeMetaId(String gaugeName, boolean everIncreasing) throws SQLException {
        synchronized (lock) {
            GaugeMeta gaugeMeta = gaugeMetas.getIfPresent(gaugeName);
            if (gaugeMeta != null) {
                if (gaugeMeta.everIncreasing() != everIncreasing) {
                    dataSource.update("update gauge_meta set ever_increasing = ? where name = ?",
                            everIncreasing, gaugeName);
                    gaugeMetas.put(gaugeName, gaugeMeta.withEverIncreasing(everIncreasing));
                }
                return gaugeMeta.id();
            }
            gaugeMeta = readGaugeMeta(gaugeName);
            if (gaugeMeta != null) {
                return gaugeMeta.id();
            }
            dataSource.update("insert into gauge_meta (name, ever_increasing) values (?, ?)",
                    gaugeName, everIncreasing);
            gaugeMeta = readGaugeMeta(gaugeName);
            checkNotNull(gaugeMeta);
            gaugeMetas.put(gaugeName, gaugeMeta);
            return gaugeMeta.id();
        }
    }

    @Nullable
    Long getGaugeMetaId(String gaugeName) throws SQLException {
        GaugeMeta gaugeMeta = gaugeMetas.getIfPresent(gaugeName);
        if (gaugeMeta != null) {
            return gaugeMeta.id();
        }
        gaugeMeta = readGaugeMeta(gaugeName);
        if (gaugeMeta == null) {
            return null;
        }
        gaugeMetas.put(gaugeName, gaugeMeta);
        return gaugeMeta.id();
    }

    private @Nullable GaugeMeta readGaugeMeta(String gaugeName) throws SQLException {
        List<GaugeMeta> gaugeMetas = dataSource.query("select id, ever_increasing from gauge_meta"
                + " where name = ?", new GaugeMetaRowMapper(), gaugeName);
        if (gaugeMetas.isEmpty()) {
            return null;
        }
        checkState(gaugeMetas.size() == 1);
        return gaugeMetas.get(0);
    }

    @Value.Immutable
    static abstract class GaugeMetaBase {
        @Value.Parameter
        abstract long id();
        @Value.Parameter
        abstract boolean everIncreasing();
    }

    private static class GaugeMetaRowMapper implements RowMapper<GaugeMeta> {
        @Override
        public GaugeMeta mapRow(ResultSet resultSet) throws SQLException {
            long id = resultSet.getLong(1);
            boolean everIncreasing = resultSet.getBoolean(2);
            return GaugeMeta.of(id, everIncreasing);
        }
    }
}
