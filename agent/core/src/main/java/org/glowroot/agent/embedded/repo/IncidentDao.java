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
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcQuery;
import org.glowroot.agent.embedded.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.ImmutableIndex;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.agent.embedded.util.Schemas.Index;
import org.glowroot.common.repo.ImmutableOpenIncident;
import org.glowroot.common.repo.ImmutableResolvedIncident;
import org.glowroot.common.repo.IncidentRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;

import static com.google.common.base.Preconditions.checkNotNull;

class IncidentDao implements IncidentRepository {

    private static final String AGENT_ID = "";

    private static final ImmutableList<Column> incidentColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("open_time", ColumnType.BIGINT),
            ImmutableColumn.of("resolve_time", ColumnType.BIGINT),
            ImmutableColumn.of("condition", ColumnType.VARBINARY),
            ImmutableColumn.of("severity", ColumnType.VARCHAR),
            ImmutableColumn.of("notification", ColumnType.VARBINARY));

    private static final ImmutableList<Index> incidentIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("incident_resolve_time_idx", ImmutableList.of("resolve_time")),
            ImmutableIndex.of("incident_condition_idx", ImmutableList.of("condition")));

    private final DataSource dataSource;

    IncidentDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;

        // upgrade from 0.9.19 to 0.9.20
        if (dataSource.tableExists("triggered_alert")) {
            dataSource.execute("drop table triggered_alert");
        }

        dataSource.syncTable("incident", incidentColumns);
        dataSource.syncIndexes("incident", incidentIndexes);
    }

    @Override
    public void insertOpenIncident(String agentRollupId, AlertCondition condition,
            AlertSeverity severity, AlertNotification notification, long openTime)
            throws Exception {
        dataSource.update("insert into incident (open_time, condition, severity, notification)"
                + " values (?, ?, ?, ?)", openTime, condition.toByteArray(),
                severity.name().toLowerCase(Locale.ENGLISH), notification.toByteArray());
    }

    @Override
    public @Nullable OpenIncident readOpenIncident(String agentRollupId, AlertCondition condition,
            AlertSeverity severity) throws Exception {
        return dataSource.query(new SingleOpenIncident(condition, severity));
    }

    @Override
    public List<OpenIncident> readOpenIncidents(String agentRollupId) throws Exception {
        return readAllOpenIncidents();
    }

    @Override
    public List<OpenIncident> readAllOpenIncidents() throws Exception {
        return dataSource.query(new OpenIncidentRowQuery());
    }

    @Override
    public void resolveIncident(OpenIncident incident, long resolveTime) throws Exception {
        dataSource.update("update incident set resolve_time = ? where condition = ?"
                + " and severity = ? and resolve_time is null", resolveTime,
                incident.condition().toByteArray(),
                incident.severity().name().toLowerCase(Locale.ENGLISH));
    }

    @Override
    public List<ResolvedIncident> readResolvedIncidents(long from) throws Exception {
        return dataSource.query(new ResolvedIncidentRowQuery(from));
    }

    void deleteResolvedIncidentsBefore(long resolvedTime) throws Exception {
        dataSource.deleteBefore("incident", "resolve_time", resolvedTime);
    }

    private static class SingleOpenIncident implements JdbcQuery</*@Nullable*/ OpenIncident> {

        private final AlertCondition condition;
        private final AlertSeverity severity;

        private SingleOpenIncident(AlertCondition condition, AlertSeverity severity) {
            this.condition = condition;
            this.severity = severity;
        }

        @Override
        public @Untainted String getSql() {
            return "select notification, open_time from incident where condition = ?"
                    + " and severity = ? and resolve_time is null";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setBytes(1, condition.toByteArray());
            preparedStatement.setString(2, severity.name().toLowerCase(Locale.ENGLISH));
        }

        @Override
        public @Nullable OpenIncident processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                return null;
            }
            byte[] notificationBytes = checkNotNull(resultSet.getBytes(1));
            long openTime = resultSet.getLong(2);
            return ImmutableOpenIncident.builder()
                    .agentRollupId(AGENT_ID)
                    .condition(condition)
                    .severity(severity)
                    .notification(AlertNotification.parseFrom(notificationBytes))
                    .openTime(openTime)
                    .build();
        }

        @Override
        public @Nullable OpenIncident valueIfDataSourceClosed() {
            return null;
        }
    }

    private static class OpenIncidentRowQuery implements JdbcRowQuery<OpenIncident> {

        @Override
        public @Untainted String getSql() {
            return "select open_time, condition, severity, notification from incident where"
                    + " resolve_time is null";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) {}

        @Override
        public OpenIncident mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            long openTime = resultSet.getLong(i++);
            byte[] conditionBytes = checkNotNull(resultSet.getBytes(i++));
            String severity = checkNotNull(resultSet.getString(i++));
            byte[] notificationBytes = checkNotNull(resultSet.getBytes(i++));
            return ImmutableOpenIncident.builder()
                    .agentRollupId(AGENT_ID)
                    .openTime(openTime)
                    .condition(AlertCondition.parseFrom(conditionBytes))
                    .severity(AlertSeverity.valueOf(severity.toUpperCase(Locale.ENGLISH)))
                    .notification(AlertNotification.parseFrom(notificationBytes))
                    .build();
        }
    }

    private static class ResolvedIncidentRowQuery implements JdbcRowQuery<ResolvedIncident> {

        private static final String AGENT_ID = "";

        private final long from;

        private ResolvedIncidentRowQuery(long from) {
            this.from = from;
        }

        @Override
        public @Untainted String getSql() {
            return "select open_time, resolve_time, condition, severity, notification from incident"
                    + " where resolve_time >= ? order by resolve_time desc";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setLong(1, from);
        }

        @Override
        public ResolvedIncident mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            long openTime = resultSet.getLong(i++);
            long resolveTime = resultSet.getLong(i++);
            byte[] conditionBytes = checkNotNull(resultSet.getBytes(i++));
            String severity = checkNotNull(resultSet.getString(i++));
            byte[] notificationBytes = checkNotNull(resultSet.getBytes(i++));
            return ImmutableResolvedIncident.builder()
                    .agentRollupId(AGENT_ID)
                    .openTime(openTime)
                    .resolveTime(resolveTime)
                    .condition(AlertCondition.parseFrom(conditionBytes))
                    .severity(AlertSeverity.valueOf(severity.toUpperCase(Locale.ENGLISH)))
                    .notification(AlertNotification.parseFrom(notificationBytes))
                    .build();
        }
    }
}
