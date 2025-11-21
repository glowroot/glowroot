/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.central.repo;

import com.datastax.oss.driver.api.core.cql.*;
import com.google.common.primitives.Ints;
import com.google.protobuf.InvalidProtocolBufferException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.util.Session;
import org.glowroot.common.Constants;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ImmutableOpenIncident;
import org.glowroot.common2.repo.ImmutableResolvedIncident;
import org.glowroot.common2.repo.IncidentRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

public class IncidentDao implements IncidentRepository {

    private final Session session;
    private final Clock clock;

    private final PreparedStatement insertOpenIncidentPS;
    private final PreparedStatement readOpenIncidentPS;
    private final PreparedStatement readOpenIncidentsPS;
    private final PreparedStatement readAllOpenIncidentsPS;
    private final PreparedStatement deleteOpenIncidentPS;

    private final PreparedStatement insertResolvedIncidentPS;
    private final PreparedStatement readRecentResolvedIncidentsPS;

    IncidentDao(Session session, Clock clock) throws Exception {
        this.session = session;
        this.clock = clock;

        session.createTableWithLCS("create table if not exists open_incident (one int,"
                + " agent_rollup_id varchar, condition blob, severity varchar, notification blob,"
                + " open_time timestamp, primary key (one, agent_rollup_id, condition, severity))");

        session.createTableWithTWCS("create table if not exists resolved_incident (one int,"
                + " resolve_time timestamp, agent_rollup_id varchar, condition blob, severity"
                + " varchar, notification blob, open_time timestamp, primary key (one,"
                + " resolve_time, agent_rollup_id, condition)) with clustering order by"
                + " (resolve_time desc)", Constants.RESOLVED_INCIDENT_EXPIRATION_HOURS, true);

        insertOpenIncidentPS = session.prepare("insert into open_incident (one, agent_rollup_id,"
                + " condition, severity, notification, open_time) values (1, ?, ?, ?, ?, ?)");
        readOpenIncidentPS = session.prepare("select notification, open_time from open_incident"
                + " where one = 1 and agent_rollup_id = ? and condition = ? and severity = ?");
        readOpenIncidentsPS = session.prepare("select condition, severity, notification, open_time"
                + " from open_incident where one = 1 and agent_rollup_id = ?");
        readAllOpenIncidentsPS = session.prepare("select agent_rollup_id, condition, severity,"
                + " notification, open_time from open_incident where one = 1");
        deleteOpenIncidentPS = session.prepare("delete from open_incident where one = 1 and"
                + " agent_rollup_id = ? and condition = ? and severity = ?");

        insertResolvedIncidentPS = session.prepare("insert into resolved_incident (one,"
                + " resolve_time, agent_rollup_id, condition, severity, notification, open_time)"
                + " values (1, ?, ?, ?, ?, ?, ?) using ttl ?");
        readRecentResolvedIncidentsPS = session.prepare("select resolve_time, agent_rollup_id,"
                + " condition, severity, notification, open_time from resolved_incident where"
                + " one = 1 and resolve_time >= ?");
    }

    @Override
    public CompletionStage<?> insertOpenIncident(String agentRollupId, AlertCondition condition,
                                   AlertSeverity severity, AlertNotification notification, long openTime, CassandraProfile profile) {
        int i = 0;
        BoundStatement boundStatement = insertOpenIncidentPS.bind()
                .setString(i++, agentRollupId)
                .setByteBuffer(i++, ByteBuffer.wrap(condition.toByteArray()))
                .setString(i++, severity.name().toLowerCase(Locale.ENGLISH))
                .setByteBuffer(i++, ByteBuffer.wrap(notification.toByteArray()))
                .setInstant(i++, Instant.ofEpochMilli(openTime));
        return session.writeAsync(boundStatement, profile);
    }

    @Override
    public CompletionStage<OpenIncident> readOpenIncident(String agentRollupId, AlertCondition condition,
                                                   AlertSeverity severity, CassandraProfile profile) {
        int i = 0;
        BoundStatement boundStatement = readOpenIncidentPS.bind()
                .setString(i++, agentRollupId)
                .setByteBuffer(i++, ByteBuffer.wrap(condition.toByteArray()))
                .setString(i++, severity.name().toLowerCase(Locale.ENGLISH));
        return session.readAsync(boundStatement, profile).thenApply(results -> {
            Row row = results.one();
            if (row == null) {
                return null;
            }
            try {
                AlertNotification notification = AlertNotification.parseFrom(checkNotNull(row.getByteBuffer(0)));
                long openTime = checkNotNull(row.getInstant(1)).toEpochMilli();
                return ImmutableOpenIncident.builder()
                        .agentRollupId(agentRollupId)
                        .condition(condition)
                        .severity(severity)
                        .notification(notification)
                        .openTime(openTime)
                        .build();
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletionStage<List<OpenIncident>> readOpenIncidents(String agentRollupId, CassandraProfile profile) {
        BoundStatement boundStatement = readOpenIncidentsPS.bind()
                .setString(0, agentRollupId);

        List<OpenIncident> openIncidents = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<OpenIncident>>> compute = new Function<>() {

            @Override
            public CompletableFuture<List<OpenIncident>> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    int i = 0;

                    try {
                        AlertCondition condition = AlertCondition.parseFrom(checkNotNull(row.getByteBuffer(i++)));
                        AlertSeverity severity = AlertSeverity
                                .valueOf(checkNotNull(row.getString(i++)).toUpperCase(Locale.ENGLISH));

                        AlertNotification notification =
                                AlertNotification.parseFrom(checkNotNull(row.getByteBuffer(i++)));
                        long openTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                        openIncidents.add(ImmutableOpenIncident.builder()
                                .agentRollupId(agentRollupId)
                                .condition(condition)
                                .severity(severity)
                                .notification(notification)
                                .openTime(openTime)
                                .build());
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(openIncidents);
            }
        };

        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    @Override
    public CompletionStage<List<OpenIncident>> readAllOpenIncidents(CassandraProfile profile) {
        BoundStatement boundStatement = readAllOpenIncidentsPS.bind();
        List<OpenIncident> openIncidents = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<OpenIncident>>> compute = new Function<AsyncResultSet, CompletableFuture<List<OpenIncident>>>() {
            @Override
            public CompletableFuture<List<OpenIncident>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    try {
                        String agentRollupId = checkNotNull(row.getString(i++));
                        AlertCondition condition = AlertCondition.parseFrom(checkNotNull(row.getByteBuffer(i++)));
                        AlertSeverity severity = AlertSeverity
                                .valueOf(checkNotNull(row.getString(i++)).toUpperCase(Locale.ENGLISH));
                        AlertNotification notification =
                                AlertNotification.parseFrom(checkNotNull(row.getByteBuffer(i++)));
                        long openTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                        openIncidents.add(ImmutableOpenIncident.builder()
                                .agentRollupId(agentRollupId)
                                .condition(condition)
                                .severity(severity)
                                .notification(notification)
                                .openTime(openTime)
                                .build());
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(openIncidents);
            }
        };
        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    @Override
    public CompletionStage<?> resolveIncident(OpenIncident openIncident, long resolveTime, CassandraProfile profile) {
        int adjustedTTL = Common.getAdjustedTTL(
                Ints.saturatedCast(
                        HOURS.toSeconds(Constants.RESOLVED_INCIDENT_EXPIRATION_HOURS)),
                resolveTime, clock);

        ByteBuffer conditionBytes = ByteBuffer.wrap(openIncident.condition().toByteArray());
        ByteBuffer notificationBytes = ByteBuffer.wrap(openIncident.notification().toByteArray());
        int i = 0;
        BoundStatement boundStatement = insertResolvedIncidentPS.bind()
                .setInstant(i++, Instant.ofEpochMilli(resolveTime))
                .setString(i++, openIncident.agentRollupId())
                .setByteBuffer(i++, conditionBytes)
                .setString(i++,
                        openIncident.severity().name().toLowerCase(Locale.ENGLISH))
                .setByteBuffer(i++, notificationBytes)
                .setInstant(i++, Instant.ofEpochMilli(openIncident.openTime()))
                .setInt(i++, adjustedTTL);
        return session.writeAsync(boundStatement, profile).thenCompose((ignore) -> {
            int j = 0;
            BoundStatement boundStatement2 = deleteOpenIncidentPS.bind()
                    .setString(j++, openIncident.agentRollupId())
                    .setByteBuffer(j++, conditionBytes)
                    .setString(j++,
                            openIncident.severity().name().toLowerCase(Locale.ENGLISH));
            return session.writeAsync(boundStatement2, profile);
        });
    }

    @Override
    public CompletionStage<List<ResolvedIncident>> readResolvedIncidents(long from) {
        BoundStatement boundStatement = readRecentResolvedIncidentsPS.bind()
                .setInstant(0, Instant.ofEpochMilli(from));
        List<ResolvedIncident> resolvedIncidents = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<ResolvedIncident>>> compute = new Function<AsyncResultSet, CompletableFuture<List<ResolvedIncident>>>() {
            @Override
            public CompletableFuture<List<ResolvedIncident>> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    int i = 0;
                    try {
                        long resolveTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                        String agentRollupId = checkNotNull(row.getString(i++));
                        AlertCondition condition = AlertCondition.parseFrom(checkNotNull(row.getByteBuffer(i++)));
                        AlertSeverity severity = AlertSeverity
                                .valueOf(checkNotNull(row.getString(i++)).toUpperCase(Locale.ENGLISH));
                        AlertNotification notification =
                                AlertNotification.parseFrom(checkNotNull(row.getByteBuffer(i++)));
                        long openTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                        resolvedIncidents.add(ImmutableResolvedIncident.builder()
                                .agentRollupId(agentRollupId)
                                .openTime(openTime)
                                .resolveTime(resolveTime)
                                .condition(condition)
                                .severity(severity)
                                .notification(notification)
                                .build());
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(resolvedIncidents);
            }
        };
        return session.readAsync(boundStatement, CassandraProfile.web).thenCompose(compute);
    }
}
