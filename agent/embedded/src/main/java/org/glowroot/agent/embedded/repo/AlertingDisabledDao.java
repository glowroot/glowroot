/*
 * Copyright 2019-2023 the original author or authors.
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
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcUpdate;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.common2.repo.AlertingDisabledRepository;
import org.glowroot.common2.repo.CassandraProfile;

import static com.google.common.base.Preconditions.checkState;

public class AlertingDisabledDao implements AlertingDisabledRepository {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("disabled_until_time", ColumnType.BIGINT));

    private final DataSource dataSource;

    AlertingDisabledDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("alerting_disabled", columns);
        init(dataSource);
    }

    @Override
    public CompletionStage<Long> getAlertingDisabledUntilTime(String agentRollupId, CassandraProfile profile) {
        try {
            return CompletableFuture.completedFuture(dataSource.queryForOptionalLong("select disabled_until_time from alerting_disabled"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletionStage<?> setAlertingDisabledUntilTime(String agentRollupId, @Nullable Long disabledUntilTime, CassandraProfile profile) {
        try {
            dataSource.update(new AlertingDisabledBinder(disabledUntilTime));
            return CompletableFuture.completedFuture(null);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    void reinitAfterDeletingDatabase() throws Exception {
        init(dataSource);
    }

    private static void init(DataSource dataSource) throws SQLException {
        long rowCount = dataSource.queryForLong("select count(*) from alerting_disabled");
        if (rowCount == 0) {
            dataSource.execute("insert into alerting_disabled (disabled_until_time) values (null)");
        } else {
            checkState(rowCount == 1);
        }
    }

    private static class AlertingDisabledBinder implements JdbcUpdate {

        private final @Nullable Long disabledUntilTime;

        private AlertingDisabledBinder(@Nullable Long disabledUntilTime) {
            this.disabledUntilTime = disabledUntilTime;
        }

        @Override
        public @Untainted String getSql() {
            return "update alerting_disabled set disabled_until_time = ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            if (disabledUntilTime == null) {
                preparedStatement.setNull(1, Types.BIGINT);
            } else {
                preparedStatement.setLong(1, disabledUntilTime);
            }
        }
    }
}
