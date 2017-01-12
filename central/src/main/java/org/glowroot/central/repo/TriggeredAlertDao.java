/*
 * Copyright 2016-2017 the original author or authors.
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
import java.util.List;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;

import org.glowroot.central.util.Sessions;
import org.glowroot.common.repo.ImmutableTriggeredAlert;
import org.glowroot.common.repo.TriggeredAlertRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;

import static com.google.common.base.Preconditions.checkNotNull;

public class TriggeredAlertDao implements TriggeredAlertRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement existsPS;
    private final PreparedStatement deletePS;
    private final PreparedStatement readPS;

    private final PreparedStatement readAllPS;

    public TriggeredAlertDao(Session session) throws Exception {
        this.session = session;

        Sessions.execute(session, "create table if not exists triggered_alert (agent_rollup_id"
                + " varchar, alert_condition blob, primary key (agent_rollup_id, alert_condition)) "
                + WITH_LCS);

        insertPS = session.prepare("insert into triggered_alert (agent_rollup_id, alert_condition)"
                + " values (?, ?)");

        existsPS = session.prepare("select agent_rollup_id from triggered_alert where"
                + " agent_rollup_id = ? and alert_condition = ?");

        deletePS = session.prepare("delete from triggered_alert where agent_rollup_id = ?"
                + " and alert_condition = ?");

        readPS = session
                .prepare("select alert_condition from triggered_alert where agent_rollup_id = ?");

        readAllPS = session.prepare("select agent_rollup_id, alert_condition from triggered_alert");
    }

    @Override
    public boolean exists(String agentRollupId, AlertCondition alertCondition) throws Exception {
        BoundStatement boundStatement = existsPS.bind();
        boundStatement.setString(0, agentRollupId);
        boundStatement.setBytes(1, ByteBuffer.wrap(alertCondition.toByteArray()));
        ResultSet results = Sessions.execute(session, boundStatement);
        return !results.isExhausted();
    }

    @Override
    public void delete(String agentRollupId, AlertCondition alertCondition) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, agentRollupId);
        boundStatement.setBytes(1, ByteBuffer.wrap(alertCondition.toByteArray()));
        Sessions.execute(session, boundStatement);
    }

    @Override
    public void insert(String agentRollupId, AlertCondition alertCondition) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setString(0, agentRollupId);
        boundStatement.setBytes(1, ByteBuffer.wrap(alertCondition.toByteArray()));
        Sessions.execute(session, boundStatement);
    }

    @Override
    public List<AlertCondition> readAlertConditions(String agentRollupId) throws Exception {
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setString(0, agentRollupId);
        ResultSet results = Sessions.execute(session, boundStatement);
        List<AlertCondition> alertConditions = Lists.newArrayList();
        for (Row row : results) {
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            alertConditions.add(AlertCondition.parseFrom(ByteString.copyFrom(bytes)));
        }
        return alertConditions;
    }

    @Override
    public List<TriggeredAlert> readAll() throws Exception {
        BoundStatement boundStatement = readAllPS.bind();
        ResultSet results = Sessions.execute(session, boundStatement);
        List<TriggeredAlert> triggeredAlerts = Lists.newArrayList();
        for (Row row : results) {
            String agentRollupId = checkNotNull(row.getString(0));
            ByteBuffer bytes = checkNotNull(row.getBytes(1));
            triggeredAlerts.add(ImmutableTriggeredAlert.builder()
                    .agentRollupId(agentRollupId)
                    .alertCondition(AlertCondition.parseFrom(ByteString.copyFrom(bytes)))
                    .build());
        }
        return triggeredAlerts;
    }
}
