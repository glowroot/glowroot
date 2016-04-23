/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage;

import java.sql.SQLException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.agent.fat.storage.util.ImmutableColumn;
import org.glowroot.agent.fat.storage.util.Schemas.Column;
import org.glowroot.agent.fat.storage.util.Schemas.ColumnType;

class GaugeNameDao {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("id", ColumnType.AUTO_IDENTITY),
            ImmutableColumn.of("gauge_name", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    private final DataSource dataSource;

    private final Object lock = new Object();

    GaugeNameDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.renameTable("gauge", "gauge_name");
        dataSource.renameColumn("gauge_name", "name", "gauge_name");
        dataSource.syncTable("gauge_name", columns);
    }

    // warning: returns -1 if data source is closing
    long updateLastCaptureTime(String gaugeName, long captureTime) throws SQLException {
        synchronized (lock) {
            Long gaugeId = getGaugeId(gaugeName);
            if (gaugeId != null) {
                dataSource.update("update gauge_name set last_capture_time = ? where id = ?",
                        captureTime, gaugeId);
                return gaugeId;
            }
            dataSource.update(
                    "insert into gauge_name (gauge_name, last_capture_time) values (?, ?)",
                    gaugeName, captureTime);
            gaugeId = getGaugeId(gaugeName);
            // gaugeId could still be null here if data source is closing
            return MoreObjects.firstNonNull(gaugeId, -1L);
        }
    }

    @Nullable
    Long getGaugeId(String gaugeName) throws SQLException {
        return dataSource.queryForOptionalLong("select id from gauge_name where gauge_name = ?",
                gaugeName);
    }

    List<String> readAllGaugeNames() throws SQLException {
        return dataSource.queryForStringList("select gauge_name from gauge_name");
    }

    void deleteAll() throws Exception {
        synchronized (lock) {
            dataSource.update("truncate table gauge_name");
        }
    }

    void deleteBefore(long captureTime) throws Exception {
        synchronized (lock) {
            dataSource.update("delete from gauge_name where last_capture_time < ?", captureTime);
        }
    }
}
