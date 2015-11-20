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

import java.sql.SQLException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.Schemas.Column;
import org.glowroot.storage.simplerepo.util.Schemas.ColumnType;

class GaugeDao {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("id", ColumnType.AUTO_IDENTITY),
            ImmutableColumn.of("name", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    private final DataSource dataSource;

    private final Object lock = new Object();

    GaugeDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("gauge", columns);
    }

    // warning: returns -1 if data source is closing
    long updateLastCaptureTime(String gaugeName, long captureTime) throws SQLException {
        synchronized (lock) {
            Long gaugeId = getGaugeId(gaugeName);
            if (gaugeId != null) {
                dataSource.update("update gauge set last_capture_time = ? where id = ?",
                        captureTime, gaugeId);
                return gaugeId;
            }
            dataSource.update("insert into gauge (name, last_capture_time) values (?, ?)",
                    gaugeName, captureTime);
            gaugeId = getGaugeId(gaugeName);
            // gaugeId could still be null here if data source is closing
            return MoreObjects.firstNonNull(gaugeId, -1L);
        }
    }

    @Nullable
    Long getGaugeId(String gaugeName) throws SQLException {
        return dataSource.queryForOptionalLong("select id from gauge where name = ?", gaugeName);
    }

    List<String> readAllGaugeNames() throws SQLException {
        return dataSource.queryForStringList("select name from gauge");
    }

    void deleteAll() throws Exception {
        synchronized (lock) {
            dataSource.update("truncate table gauge");
        }
    }

    void deleteBefore(long captureTime) throws Exception {
        synchronized (lock) {
            dataSource.update("delete from gauge where last_capture_time < ?", captureTime);
        }
    }
}
