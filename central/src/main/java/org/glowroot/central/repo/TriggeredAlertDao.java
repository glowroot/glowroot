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
package org.glowroot.central.repo;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.google.common.primitives.Ints;

import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.TriggeredAlertRepository;

import static java.util.concurrent.TimeUnit.HOURS;

public class TriggeredAlertDao implements TriggeredAlertRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final ConfigRepository configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement existsPS;
    private final PreparedStatement deletePS;

    public TriggeredAlertDao(Session session, ConfigRepository configRepository) {
        this.session = session;
        this.configRepository = configRepository;

        session.execute("create table if not exists triggered_alert (agent_rollup varchar,"
                + " alert_config_version varchar, primary key (agent_rollup,"
                + " alert_config_version)) " + WITH_LCS);

        insertPS = session.prepare("insert into triggered_alert (agent_rollup,"
                + " alert_config_version) values (?, ?) using ttl ?");

        existsPS = session.prepare("select agent_rollup from triggered_alert where agent_rollup = ?"
                + " and alert_config_version = ?");

        deletePS = session.prepare("delete from triggered_alert where agent_rollup = ?"
                + " and alert_config_version = ?");
    }

    @Override
    public boolean exists(String agentRollupId, String version) throws Exception {
        BoundStatement boundStatement = existsPS.bind();
        boundStatement.setString(0, agentRollupId);
        boundStatement.setString(1, version);
        ResultSet results = session.execute(boundStatement);
        return !results.isExhausted();
    }

    @Override
    public void delete(String agentRollupId, String version) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, agentRollupId);
        boundStatement.setString(1, version);
        session.execute(boundStatement);
    }

    @Override
    public void insert(String agentRollupId, String version) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, version);
        boundStatement.setInt(i++, getMaxTTL());
        session.execute(boundStatement);
    }

    private int getMaxTTL() {
        long maxTTL = 0;
        for (long expirationHours : configRepository.getStorageConfig().rollupExpirationHours()) {
            if (expirationHours == 0) {
                // zero value expiration/TTL means never expire
                return 0;
            }
            maxTTL = Math.max(maxTTL, HOURS.toSeconds(expirationHours));
        }
        // intentionally not accounting for rateLimiter
        return Ints.saturatedCast(maxTTL);
    }
}
