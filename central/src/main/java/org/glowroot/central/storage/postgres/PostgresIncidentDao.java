/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.common.primitives.Ints;
import com.google.protobuf.InvalidProtocolBufferException;
import org.glowroot.common.Constants;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ImmutableOpenIncident;
import org.glowroot.common2.repo.ImmutableResolvedIncident;
import org.glowroot.common2.repo.IncidentRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Postgres port of Cassandra {@code IncidentDao}.
 */
public class PostgresIncidentDao implements IncidentRepository {

    private final DataSource dataSource;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresIncidentDao(DataSource dataSource, Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletionStage<?> insertOpenIncident(String agentRollupId, AlertCondition condition,
            AlertSeverity severity, AlertNotification notification, long openTime,
            CassandraProfile profile) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "insert into open_incident (agent_rollup_id, condition, severity, "
                                    + "notification, open_time) values (?, ?, ?, ?, ?) "
                                    + "on conflict (agent_rollup_id, condition, severity) do update set "
                                    + "notification = excluded.notification, "
                                    + "open_time = excluded.open_time")) {
                ps.setString(1, agentRollupId);
                ps.setBytes(2, condition.toByteArray());
                ps.setString(3, severity.name().toLowerCase(Locale.ENGLISH));
                ps.setBytes(4, notification.toByteArray());
                ps.setTimestamp(5, new Timestamp(openTime));
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    @Override
    public CompletionStage<OpenIncident> readOpenIncident(String agentRollupId,
            AlertCondition condition, AlertSeverity severity, CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select notification, open_time from open_incident "
                                    + "where agent_rollup_id = ? and condition = ? and severity = ?")) {
                ps.setString(1, agentRollupId);
                ps.setBytes(2, condition.toByteArray());
                ps.setString(3, severity.name().toLowerCase(Locale.ENGLISH));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    AlertNotification notification =
                            AlertNotification.parseFrom(rs.getBytes(1));
                    long openTime = rs.getTimestamp(2).getTime();
                    return ImmutableOpenIncident.builder()
                            .agentRollupId(agentRollupId)
                            .condition(condition)
                            .severity(severity)
                            .notification(notification)
                            .openTime(openTime)
                            .build();
                }
            } catch (SQLException | InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<List<OpenIncident>> readOpenIncidents(String agentRollupId,
            CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select condition, severity, notification, open_time from open_incident "
                                    + "where agent_rollup_id = ?")) {
                ps.setString(1, agentRollupId);
                return readOpenIncidents(ps, agentRollupId, true);
            } catch (SQLException | InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<List<OpenIncident>> readAllOpenIncidents(CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select agent_rollup_id, condition, severity, notification, open_time "
                                    + "from open_incident")) {
                return readOpenIncidents(ps, null, false);
            } catch (SQLException | InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<?> resolveIncident(OpenIncident openIncident, long resolveTime,
            CassandraProfile profile) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            int ttlSeconds = Ints.saturatedCast(
                    HOURS.toSeconds(Constants.RESOLVED_INCIDENT_EXPIRATION_HOURS));
            Timestamp expiresAt =
                    PostgresExpires.fromTtlSeconds(ttlSeconds, resolveTime, clock);
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "insert into resolved_incident (resolve_time, agent_rollup_id, "
                                    + "condition, severity, notification, open_time, expires_at) "
                                    + "values (?, ?, ?, ?, ?, ?, ?) "
                                    + "on conflict (resolve_time, agent_rollup_id, condition) do update set "
                                    + "severity = excluded.severity, "
                                    + "notification = excluded.notification, "
                                    + "open_time = excluded.open_time, "
                                    + "expires_at = excluded.expires_at")) {
                        insert.setTimestamp(1, new Timestamp(resolveTime));
                        insert.setString(2, openIncident.agentRollupId());
                        insert.setBytes(3, openIncident.condition().toByteArray());
                        insert.setString(4,
                                openIncident.severity().name().toLowerCase(Locale.ENGLISH));
                        insert.setBytes(5, openIncident.notification().toByteArray());
                        insert.setTimestamp(6, new Timestamp(openIncident.openTime()));
                        insert.setTimestamp(7, expiresAt);
                        insert.executeUpdate();
                    }
                    try (PreparedStatement delete = connection.prepareStatement(
                            "delete from open_incident where agent_rollup_id = ? "
                                    + "and condition = ? and severity = ?")) {
                        delete.setString(1, openIncident.agentRollupId());
                        delete.setBytes(2, openIncident.condition().toByteArray());
                        delete.setString(3,
                                openIncident.severity().name().toLowerCase(Locale.ENGLISH));
                        delete.executeUpdate();
                    }
                    connection.commit();
                    future.complete(null);
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    @Override
    public CompletionStage<List<ResolvedIncident>> readResolvedIncidents(long from) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select resolve_time, agent_rollup_id, condition, severity, "
                                    + "notification, open_time from resolved_incident "
                                    + "where resolve_time >= ? and expires_at > ? "
                                    + "order by resolve_time desc")) {
                long now = clock.currentTimeMillis();
                ps.setTimestamp(1, new Timestamp(from));
                ps.setTimestamp(2, new Timestamp(now));
                List<ResolvedIncident> resolved = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long resolveTime = rs.getTimestamp(1).getTime();
                        String agentRollupId = rs.getString(2);
                        AlertCondition condition = AlertCondition.parseFrom(rs.getBytes(3));
                        AlertSeverity severity = AlertSeverity
                                .valueOf(rs.getString(4).toUpperCase(Locale.ENGLISH));
                        AlertNotification notification =
                                AlertNotification.parseFrom(rs.getBytes(5));
                        long openTime = rs.getTimestamp(6).getTime();
                        resolved.add(ImmutableResolvedIncident.builder()
                                .agentRollupId(agentRollupId)
                                .openTime(openTime)
                                .resolveTime(resolveTime)
                                .condition(condition)
                                .severity(severity)
                                .notification(notification)
                                .build());
                    }
                }
                return resolved;
            } catch (SQLException | InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    private static List<OpenIncident> readOpenIncidents(PreparedStatement ps,
            String fixedAgentRollupId, boolean agentIdFixed)
            throws SQLException, InvalidProtocolBufferException {
        List<OpenIncident> openIncidents = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int i = 1;
                String agentRollupId;
                if (agentIdFixed) {
                    agentRollupId = fixedAgentRollupId;
                } else {
                    agentRollupId = rs.getString(i++);
                }
                AlertCondition condition = AlertCondition.parseFrom(rs.getBytes(i++));
                AlertSeverity severity =
                        AlertSeverity.valueOf(rs.getString(i++).toUpperCase(Locale.ENGLISH));
                AlertNotification notification = AlertNotification.parseFrom(rs.getBytes(i++));
                long openTime = rs.getTimestamp(i++).getTime();
                openIncidents.add(ImmutableOpenIncident.builder()
                        .agentRollupId(agentRollupId)
                        .condition(condition)
                        .severity(severity)
                        .notification(notification)
                        .openTime(openTime)
                        .build());
            }
        }
        return openIncidents;
    }
}
