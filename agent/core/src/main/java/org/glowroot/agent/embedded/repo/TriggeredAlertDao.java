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
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.ImmutableIndex;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.agent.embedded.util.Schemas.Index;
import org.glowroot.common.repo.ImmutableTriggeredAlert;
import org.glowroot.common.repo.TriggeredAlertRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;

import static com.google.common.base.Preconditions.checkNotNull;

class TriggeredAlertDao implements TriggeredAlertRepository {

    private static final ImmutableList<Column> triggeredAlertColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("alert_condition", ColumnType.VARBINARY));

    private static final ImmutableList<Index> triggeredAlertIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("triggered_alert_idx", ImmutableList.of("alert_condition")));

    private final DataSource dataSource;

    TriggeredAlertDao(DataSource dataSource, @Nullable Integer schemaVersion) throws Exception {
        this.dataSource = dataSource;
        if (dataSource.columnExists("triggered_alert", "alert_config_version")) {
            // left over table before it was removed in 0.9.10 (now added back in 0.9.16)
            dataSource.execute("drop table triggered_alert");
        }
        dataSource.syncTable("triggered_alert", triggeredAlertColumns);
        dataSource.syncIndexes("triggered_alert", triggeredAlertIndexes);
        if (schemaVersion != null && schemaVersion < 2) {
            // this is needed because of alert_condition change from OldAlertConfig to
            // AlertCondition in 0.9.18
            dataSource.update("truncate table triggered_alert");
        }
    }

    @Override
    public List<TriggeredAlert> readAll() throws Exception {
        return dataSource.query(new TriggeredAlertRowQuery());
    }

    @Override
    public void insert(String agentRollupId, AlertCondition alertCondition) throws Exception {
        dataSource.update("insert into triggered_alert (alert_condition) values (?)",
                alertCondition.toByteArray());
    }

    @Override
    public boolean exists(String agentRollupId, AlertCondition alertCondition) throws Exception {
        return dataSource.queryForExists("select 1 from triggered_alert where alert_condition = ?",
                alertCondition.toByteArray());
    }

    @Override
    public void delete(String agentRollupId, AlertCondition alertCondition) throws Exception {
        dataSource.update("delete from triggered_alert where alert_condition = ?",
                alertCondition.toByteArray());
    }

    @Override
    public List<AlertCondition> readAlertConditions(String agentRollupId) throws Exception {
        return dataSource.query(new AlertConditionRowQuery());
    }

    private static class AlertConditionRowQuery implements JdbcRowQuery<AlertCondition> {

        @Override
        public @Untainted String getSql() {
            return "select alert_condition from triggered_alert";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) {}

        @Override
        public AlertCondition mapRow(ResultSet resultSet) throws Exception {
            byte[] bytes = checkNotNull(resultSet.getBytes(1));
            return AlertCondition.parseFrom(bytes);
        }
    }

    private static class TriggeredAlertRowQuery implements JdbcRowQuery<TriggeredAlert> {

        private static final String AGENT_ID = "";

        @Override
        public @Untainted String getSql() {
            return "select alert_condition from triggered_alert";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) {}

        @Override
        public TriggeredAlert mapRow(ResultSet resultSet) throws Exception {
            byte[] bytes = checkNotNull(resultSet.getBytes(1));
            return ImmutableTriggeredAlert.builder()
                    .agentRollupId(AGENT_ID)
                    .alertCondition(AlertCondition.parseFrom(ByteString.copyFrom(bytes)))
                    .build();
        }
    }
}
