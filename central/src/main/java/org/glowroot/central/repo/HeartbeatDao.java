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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.HOURS;

public class HeartbeatDao {

    static final int EXPIRATION_HOURS = 24;

    private static final int TTL = (int) HOURS.toSeconds(EXPIRATION_HOURS);

    private final Session session;
    private final Clock clock;

    private final PreparedStatement insertPS;
    private final PreparedStatement existsPS;

    HeartbeatDao(Session session, Clock clock) throws InterruptedException {
        this.session = session;
        this.clock = clock;

        session.createTableWithTWCS("create table if not exists heartbeat (agent_id varchar,"
                + " central_capture_time timestamp, primary key (agent_id, central_capture_time))",
                EXPIRATION_HOURS);
        insertPS = session.prepare(
                "insert into heartbeat (agent_id, central_capture_time) values (?, ?) using ttl ?");
        existsPS = session.prepare("select central_capture_time from heartbeat where agent_id = ?"
                + " and central_capture_time > ? and central_capture_time <= ? limit 1");
    }

    public void store(String agentId) throws Exception {
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
        List<Future<?>> futures = new ArrayList<>();
        for (String agentRollupId : agentRollupIds) {
            int i = 0;
            BoundStatement boundStatement = insertPS.bind()
                .setString(i++, agentRollupId)
                .setInstant(i++, Instant.ofEpochMilli(clock.currentTimeMillis()))
                .setInt(i++, TTL);
            futures.add(session.writeAsync(boundStatement));
        }
        MoreFutures.waitForAll(futures);
    }

    public boolean exists(String agentRollupId, long centralCaptureFrom, long centralCaptureTo)
            throws Exception {
        int i = 0;
        BoundStatement boundStatement = existsPS.bind()
            .setString(i++, agentRollupId)
            .setInstant(i++, Instant.ofEpochMilli(centralCaptureFrom))
            .setInstant(i++, Instant.ofEpochMilli(centralCaptureTo));
        return session.read(boundStatement).one() != null;
    }
}
