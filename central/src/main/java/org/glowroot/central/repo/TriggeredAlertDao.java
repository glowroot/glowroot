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

import java.util.List;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.Lists;

import static com.google.common.base.Preconditions.checkNotNull;

public class TriggeredAlertDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement existsPS;
    private final PreparedStatement deletePS;
    private final PreparedStatement readPS;

    public TriggeredAlertDao(Session session) {
        this.session = session;

        session.execute("create table if not exists triggered_alert (agent_rollup_id varchar,"
                + " alert_config_id varchar, primary key (agent_rollup_id, alert_config_id)) "
                + WITH_LCS);

        insertPS = session.prepare("insert into triggered_alert (agent_rollup_id,"
                + " alert_config_id) values (?, ?)");

        existsPS = session.prepare("select agent_rollup_id from triggered_alert where"
                + " agent_rollup_id = ? and alert_config_id = ?");

        deletePS = session.prepare("delete from triggered_alert where agent_rollup_id = ?"
                + " and alert_config_id = ?");

        readPS = session
                .prepare("select alert_config_id from triggered_alert where agent_rollup_id = ?");
    }

    public boolean exists(String agentRollupId, String alertConfigId) throws Exception {
        BoundStatement boundStatement = existsPS.bind();
        boundStatement.setString(0, agentRollupId);
        boundStatement.setString(1, alertConfigId);
        ResultSet results = session.execute(boundStatement);
        return !results.isExhausted();
    }

    public void delete(String agentRollupId, String alertConfigId) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, agentRollupId);
        boundStatement.setString(1, alertConfigId);
        session.execute(boundStatement);
    }

    public void insert(String agentRollupId, String alertConfigId) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, alertConfigId);
        session.execute(boundStatement);
    }

    public List<String> read(String agentRollupId) throws Exception {
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setString(0, agentRollupId);
        ResultSet results = session.execute(boundStatement);
        List<String> alertConfigIds = Lists.newArrayList();
        for (Row row : results) {
            alertConfigIds.add(checkNotNull(row.getString(0)));
        }
        return alertConfigIds;
    }
}
