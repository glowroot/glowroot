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

import java.sql.SQLException;
import java.sql.Types;

import com.google.common.collect.ImmutableList;

import org.glowroot.server.repo.TriggeredAlertRepository;
import org.glowroot.server.simplerepo.util.DataSource;
import org.glowroot.server.simplerepo.util.ImmutableColumn;
import org.glowroot.server.simplerepo.util.ImmutableIndex;
import org.glowroot.server.simplerepo.util.Schemas.Column;
import org.glowroot.server.simplerepo.util.Schemas.Index;

class TriggeredAlertDao implements TriggeredAlertRepository {

    private static final ImmutableList<Column> triggeredAlertColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("alert_config_version", Types.VARCHAR),
            ImmutableColumn.of("end_time", Types.BIGINT));

    private static final ImmutableList<Index> triggeredAlertIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("triggered_alert_idx", ImmutableList.of("alert_config_version")));

    private final DataSource dataSource;

    TriggeredAlertDao(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dataSource.syncTable("triggered_alert", triggeredAlertColumns);
        dataSource.syncIndexes("triggered_alert", triggeredAlertIndexes);
    }

    @Override
    public void insert(String version, long endTime) throws SQLException {
        dataSource.update(
                "insert into triggered_alert (alert_config_version, end_time) values (?, ?)",
                version, endTime);
    }

    @Override
    public boolean exists(String version) throws Exception {
        return dataSource.queryForExists(
                "select 1 from triggered_alert where alert_config_version = ?", version);
    }

    @Override
    public void delete(String version) throws SQLException {
        dataSource.update("delete from triggered_alert where alert_config_version = ?", version);
    }

    // TODO expire triggered alerts after 1 day
    // partly because rows could get stranded if an alert is deleted (or its version changes)
}
