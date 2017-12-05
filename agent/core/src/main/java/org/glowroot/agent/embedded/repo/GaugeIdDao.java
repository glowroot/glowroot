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

import java.sql.SQLException;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.ImmutableIndex;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.agent.embedded.util.Schemas.Index;

import static java.util.concurrent.TimeUnit.DAYS;

class GaugeIdDao {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("gauge_id", ColumnType.AUTO_IDENTITY),
            ImmutableColumn.of("gauge_name", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    private static final ImmutableList<Index> indexes = ImmutableList.<Index>of(
            ImmutableIndex.of("gauge_id_gauge_id_idx", ImmutableList.of("gauge_id")),
            ImmutableIndex.of("gauge_id_gauge_name_idx", ImmutableList.of("gauge_name")));

    private final DataSource dataSource;

    private final Cache<String, Long> lastCaptureTimeUpdatedInThePastDay =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(1, DAYS)
                    .maximumSize(10000)
                    .build();

    private final Object lock = new Object();

    GaugeIdDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        if (dataSource.columnExists("gauge_name", "last_capture_time")) {
            dataSource.renameTable("gauge_name", "gauge_id");
            dataSource.renameColumn("gauge_id", "id", "gauge_id");
        }
        dataSource.syncTable("gauge_id", columns);
        dataSource.syncIndexes("gauge_id", indexes);
    }

    // warning: returns -1 if data source is closing
    long updateLastCaptureTime(String gaugeName, long captureTime) throws SQLException {
        Long gaugeId = lastCaptureTimeUpdatedInThePastDay.getIfPresent(gaugeName);
        if (gaugeId != null) {
            return gaugeId;
        }
        synchronized (lock) {
            gaugeId = dataSource.queryForOptionalLong(
                    "select gauge_id from gauge_id where gauge_name = ?", gaugeName);
            if (gaugeId == null) {
                dataSource.update(
                        "insert into gauge_id (gauge_name, last_capture_time) values (?, ?)",
                        gaugeName, captureTime);
                gaugeId = dataSource.queryForOptionalLong(
                        "select gauge_id from gauge_id where gauge_name = ?", gaugeName);
                if (gaugeId == null) {
                    // data source is closing
                    return -1;
                }
            } else {
                dataSource.update("update gauge_id set last_capture_time = ? where gauge_id = ?",
                        captureTime, gaugeId);
            }
        }
        lastCaptureTimeUpdatedInThePastDay.put(gaugeName, gaugeId);
        return gaugeId;
    }

    @Nullable
    Long getGaugeId(String gaugeName) throws SQLException {
        Long gaugeId = lastCaptureTimeUpdatedInThePastDay.getIfPresent(gaugeName);
        if (gaugeId != null) {
            return gaugeId;
        }
        // don't put back into cache, since this is old, and if it expires, will get new gauge id
        return dataSource.queryForOptionalLong("select gauge_id from gauge_id where gauge_name = ?",
                gaugeName);
    }

    void deleteBefore(long captureTime) throws Exception {
        synchronized (lock) {
            // subtracting 1 day to account for rate limiting of updates
            dataSource.update("delete from gauge_id where last_capture_time < ?",
                    captureTime - DAYS.toMillis(1));
        }
    }

    void invalidateCache() {
        lastCaptureTimeUpdatedInThePastDay.invalidateAll();
    }
}
