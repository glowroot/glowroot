/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.server.storage;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;

import org.glowroot.storage.repo.TriggeredAlertRepository;

public class TriggeredAlertDao implements TriggeredAlertRepository {

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement existsPS;
    private final PreparedStatement deletePS;

    public TriggeredAlertDao(Session session) {
        this.session = session;

        session.execute("create table if not exists triggered_alert (agent_rollup varchar,"
                + " alert_config_version varchar, primary key (agent_rollup,"
                + " alert_config_version))");

        insertPS = session.prepare("insert into triggered_alert (agent_rollup,"
                + " alert_config_version) values (?, ?)");

        existsPS = session.prepare("select agent_rollup from triggered_alert where agent_rollup = ?"
                + " and alert_config_version = ?");

        deletePS = session.prepare("delete from triggered_alert where agent_rollup = ?"
                + " and alert_config_version = ?");
    }

    @Override
    public boolean exists(String agentRollup, String version) throws Exception {
        BoundStatement boundStatement = existsPS.bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, version);
        ResultSet results = session.execute(boundStatement);
        return !results.isExhausted();
    }

    @Override
    public void delete(String agentRollup, String version) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, version);
        session.execute(boundStatement);
    }

    @Override
    public void insert(String agentRollup, String version) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, version);
        session.execute(boundStatement);
    }
}
