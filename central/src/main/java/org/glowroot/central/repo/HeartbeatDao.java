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

import java.util.Date;
import java.util.List;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;

import org.glowroot.central.util.Sessions;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.HOURS;

public class HeartbeatDao {

    static final int EXPIRATION_HOURS = 24;

    private static final int TTL = (int) HOURS.toMillis(EXPIRATION_HOURS);

    private final Session session;
    private final AgentDao agentDao;
    private final Clock clock;

    private final PreparedStatement insertPS;
    private final PreparedStatement existsPS;

    public HeartbeatDao(Session session, AgentDao agentDao, Clock clock) throws Exception {
        this.session = session;
        this.agentDao = agentDao;
        this.clock = clock;

        Sessions.createTableWithTWCS(session, "create table if not exists heartbeat"
                + " (agent_id varchar, central_capture_time timestamp, primary key (agent_id,"
                + " central_capture_time))", 24);
        insertPS = session.prepare(
                "insert into heartbeat (agent_id, central_capture_time) values (?, ?) using ttl ?");
        existsPS = session.prepare("select central_capture_time from heartbeat where agent_id = ?"
                + " and central_capture_time > ? and central_capture_time <= ? limit 1");
    }

    public void store(String agentId) throws Exception {
        List<String> agentRollupIds = agentDao.readAgentRollupIds(agentId);
        for (String agentRollupId : agentRollupIds) {
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setTimestamp(i++, new Date(clock.currentTimeMillis()));
            boundStatement.setInt(i++, TTL);
            Sessions.execute(session, boundStatement);
        }
    }

    public boolean exists(String agentRollupId, long centralCaptureFrom, long centralCaptureTo)
            throws Exception {
        BoundStatement boundStatement = existsPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setTimestamp(i++, new Date(centralCaptureFrom));
        boundStatement.setTimestamp(i++, new Date(centralCaptureTo));
        return !Sessions.execute(session, boundStatement).isExhausted();
    }
}
