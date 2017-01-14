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

import org.glowroot.common.repo.TriggeredAlertRepository;

import static com.google.common.base.Preconditions.checkNotNull;

public class TriggeredAlertDao implements TriggeredAlertRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement existsPS;
    private final PreparedStatement deletePS;
    private final PreparedStatement readPS;

    public TriggeredAlertDao(Session session) {
        this.session = session;

        session.execute("create table if not exists triggered_alert (agent_rollup varchar,"
                + " alert_config_version varchar, primary key (agent_rollup,"
                + " alert_config_version)) " + WITH_LCS);

        insertPS = session.prepare("insert into triggered_alert (agent_rollup,"
                + " alert_config_version) values (?, ?)");

        existsPS = session.prepare("select agent_rollup from triggered_alert where agent_rollup = ?"
                + " and alert_config_version = ?");

        deletePS = session.prepare("delete from triggered_alert where agent_rollup = ?"
                + " and alert_config_version = ?");

        readPS = session
                .prepare("select alert_config_version from triggered_alert where agent_rollup = ?");
    }

    @Override
    public boolean exists(String agentId, String alertConfigVersion) throws Exception {
        BoundStatement boundStatement = existsPS.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, alertConfigVersion);
        ResultSet results = session.execute(boundStatement);
        return !results.isExhausted();
    }

    @Override
    public void delete(String agentId, String alertConfigVersion) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, agentId);
        boundStatement.setString(1, alertConfigVersion);
        session.execute(boundStatement);
    }

    @Override
    public void insert(String agentId, String alertConfigVersion) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, alertConfigVersion);
        session.execute(boundStatement);
    }

    @Override
    public List<String> read(String agentId) throws Exception {
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        List<String> alertConfigVersions = Lists.newArrayList();
        for (Row row : results) {
            alertConfigVersions.add(checkNotNull(row.getString(0)));
        }
        return alertConfigVersions;
    }
}
