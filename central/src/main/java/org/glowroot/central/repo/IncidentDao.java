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

import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;

import org.glowroot.central.util.Session;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.repo.ImmutableOpenIncident;
import org.glowroot.common.repo.ImmutableResolvedIncident;
import org.glowroot.common.repo.IncidentRepository;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

public class IncidentDao implements IncidentRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

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

        session.execute("create table if not exists open_incident (one int,"
                + " agent_rollup_id varchar, condition blob, severity varchar, notification blob,"
                + " open_time timestamp, primary key (one, agent_rollup_id, condition, severity)) "
                + WITH_LCS);

        session.createTableWithTWCS("create table if not exists resolved_incident (one int,"
                + " resolve_time timestamp, agent_rollup_id varchar, condition blob,"
                + " severity varchar, notification blob, open_time timestamp, primary key (one,"
                + " resolve_time, agent_rollup_id, condition)) with clustering order by"
                + " (resolve_time desc)", StorageConfig.RESOLVED_INCIDENT_EXPIRATION_HOURS, true);

        insertOpenIncidentPS = session.prepare("insert into open_incident (one, agent_rollup_id,"
                + " condition, severity, notification, open_time) values (1, ?, ?, ?, ?, ?)");
        readOpenIncidentPS = session.prepare("select notification, open_time from open_incident"
                + " where one = 1 and agent_rollup_id = ? and condition = ? and severity = ?");
        readOpenIncidentsPS = session.prepare("select condition, severity, notification, open_time"
                + " from open_incident where one = 1 and agent_rollup_id = ?");
        readAllOpenIncidentsPS = session.prepare("select agent_rollup_id, condition, severity,"
                + " notification, open_time from open_incident where one = 1");
        deleteOpenIncidentPS = session.prepare("delete from open_incident where one = 1"
                + " and agent_rollup_id = ? and condition = ? and severity = ?");

        insertResolvedIncidentPS = session.prepare("insert into resolved_incident (one,"
                + " resolve_time, agent_rollup_id, condition, severity, notification, open_time)"
                + " values (1, ?, ?, ?, ?, ?, ?) using ttl ?");
        readRecentResolvedIncidentsPS = session.prepare("select resolve_time, agent_rollup_id,"
                + " condition, severity, notification, open_time from resolved_incident where"
                + " one = 1 and resolve_time >= ?");
    }

    @Override
    public void insertOpenIncident(String agentRollupId, AlertCondition condition,
            AlertSeverity severity, AlertNotification notification, long openTime)
            throws Exception {
        BoundStatement boundStatement = insertOpenIncidentPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(condition.toByteArray()));
        boundStatement.setString(i++, severity.name().toLowerCase(Locale.ENGLISH));
        boundStatement.setBytes(i++, ByteBuffer.wrap(notification.toByteArray()));
        boundStatement.setTimestamp(i++, new Date(openTime));
        session.execute(boundStatement);
    }

    @Override
    public @Nullable OpenIncident readOpenIncident(String agentRollupId, AlertCondition condition,
            AlertSeverity severity) throws Exception {
        BoundStatement boundStatement = readOpenIncidentPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(condition.toByteArray()));
        boundStatement.setString(i++, severity.name().toLowerCase(Locale.ENGLISH));
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        AlertNotification notification = AlertNotification.parseFrom(checkNotNull(row.getBytes(0)));
        long openTime = checkNotNull(row.getTimestamp(1)).getTime();
        return ImmutableOpenIncident.builder()
                .agentRollupId(agentRollupId)
                .condition(condition)
                .severity(severity)
                .notification(notification)
                .openTime(openTime)
                .build();
    }

    @Override
    public List<OpenIncident> readOpenIncidents(String agentRollupId) throws Exception {
        BoundStatement boundStatement = readOpenIncidentsPS.bind();
        boundStatement.setString(0, agentRollupId);
        ResultSet results = session.execute(boundStatement);
        List<OpenIncident> openIncidents = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            AlertCondition condition = AlertCondition.parseFrom(checkNotNull(row.getBytes(i++)));
            AlertSeverity severity = AlertSeverity
                    .valueOf(checkNotNull(row.getString(i++)).toUpperCase(Locale.ENGLISH));
            AlertNotification notification =
                    AlertNotification.parseFrom(checkNotNull(row.getBytes(i++)));
            long openTime = checkNotNull(row.getTimestamp(i++)).getTime();
            openIncidents.add(ImmutableOpenIncident.builder()
                    .agentRollupId(agentRollupId)
                    .condition(condition)
                    .severity(severity)
                    .notification(notification)
                    .openTime(openTime)
                    .build());
        }
        return openIncidents;
    }

    @Override
    public List<OpenIncident> readAllOpenIncidents() throws Exception {
        BoundStatement boundStatement = readAllOpenIncidentsPS.bind();
        ResultSet results = session.execute(boundStatement);
        List<OpenIncident> openIncidents = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            AlertCondition condition = AlertCondition.parseFrom(checkNotNull(row.getBytes(i++)));
            AlertSeverity severity = AlertSeverity
                    .valueOf(checkNotNull(row.getString(i++)).toUpperCase(Locale.ENGLISH));
            AlertNotification notification =
                    AlertNotification.parseFrom(checkNotNull(row.getBytes(i++)));
            long openTime = checkNotNull(row.getTimestamp(i++)).getTime();
            openIncidents.add(ImmutableOpenIncident.builder()
                    .agentRollupId(agentRollupId)
                    .condition(condition)
                    .severity(severity)
                    .notification(notification)
                    .openTime(openTime)
                    .build());
        }
        return openIncidents;
    }

    @Override
    public void resolveIncident(OpenIncident openIncident, long resolveTime) throws Exception {
        int adjustedTTL = Common.getAdjustedTTL(
                Ints.saturatedCast(
                        HOURS.toSeconds(StorageConfig.RESOLVED_INCIDENT_EXPIRATION_HOURS)),
                resolveTime, clock);

        BoundStatement boundStatement = insertResolvedIncidentPS.bind();
        int i = 0;
        boundStatement.setTimestamp(i++, new Date(resolveTime));
        boundStatement.setString(i++, openIncident.agentRollupId());
        ByteBuffer conditionBytes = ByteBuffer.wrap(openIncident.condition().toByteArray());
        boundStatement.setBytes(i++, conditionBytes);
        boundStatement.setString(i++,
                openIncident.severity().name().toLowerCase(Locale.ENGLISH));
        ByteBuffer notificationBytes = ByteBuffer.wrap(openIncident.notification().toByteArray());
        boundStatement.setBytes(i++, notificationBytes);
        boundStatement.setTimestamp(i++, new Date(openIncident.openTime()));
        boundStatement.setInt(i++, adjustedTTL);
        session.execute(boundStatement);

        boundStatement = deleteOpenIncidentPS.bind();
        i = 0;
        boundStatement.setString(i++, openIncident.agentRollupId());
        boundStatement.setBytes(i++, conditionBytes);
        boundStatement.setString(i++,
                openIncident.severity().name().toLowerCase(Locale.ENGLISH));
        session.execute(boundStatement);
    }

    @Override
    public List<ResolvedIncident> readResolvedIncidents(long from) throws Exception {
        BoundStatement boundStatement = readRecentResolvedIncidentsPS.bind();
        boundStatement.setTimestamp(0, new Date(from));
        ResultSet results = session.execute(boundStatement);
        List<ResolvedIncident> resolvedIncidents = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            long resolveTime = checkNotNull(row.getTimestamp(i++)).getTime();
            String agentRollupId = checkNotNull(row.getString(i++));
            AlertCondition condition = AlertCondition.parseFrom(checkNotNull(row.getBytes(i++)));
            AlertSeverity severity = AlertSeverity
                    .valueOf(checkNotNull(row.getString(i++)).toUpperCase(Locale.ENGLISH));
            AlertNotification notification =
                    AlertNotification.parseFrom(checkNotNull(row.getBytes(i++)));
            long openTime = checkNotNull(row.getTimestamp(i++)).getTime();
            resolvedIncidents.add(ImmutableResolvedIncident.builder()
                    .agentRollupId(agentRollupId)
                    .openTime(openTime)
                    .resolveTime(resolveTime)
                    .condition(condition)
                    .severity(severity)
                    .notification(notification)
                    .build());
        }
        return resolvedIncidents;
    }
}
