/*
 * Copyright 2015-2017 the original author or authors.
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
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import org.immutables.value.Value;

import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

class GaugeNameDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final ConfigRepository configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<GaugeNameKey> rateLimiter = new RateLimiter<>();

    GaugeNameDao(Session session, ConfigRepository configRepository) {
        this.session = session;
        this.configRepository = configRepository;

        session.execute("create table if not exists gauge_name (agent_rollup varchar,"
                + " gauge_name varchar, primary key (agent_rollup, gauge_name)) " + WITH_LCS);

        insertPS = session.prepare("insert into gauge_name (agent_rollup, gauge_name)"
                + " values (?, ?) using ttl ?");
        readPS = session.prepare("select gauge_name from gauge_name where agent_rollup = ?");
    }

    List<String> getGaugeNames(String agentRollupId) {
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setString(0, agentRollupId);
        ResultSet results = session.execute(boundStatement);
        List<String> gaugeNames = Lists.newArrayList();
        for (Row row : results) {
            gaugeNames.add(checkNotNull(row.getString(0)));
        }
        return gaugeNames;
    }

    List<ResultSetFuture> store(String agentRollupId, String gaugeName) throws Exception {
        GaugeNameKey rateLimiterKey = ImmutableGaugeNameKey.of(agentRollupId, gaugeName);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return ImmutableList.of();
        }
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setInt(i++, getMaxTTL());
        return ImmutableList.of(Sessions.executeAsyncWithOnFailure(session, boundStatement,
                () -> rateLimiter.invalidate(rateLimiterKey)));
    }

    private int getMaxTTL() throws Exception {
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

    @Value.Immutable
    @Styles.AllParameters
    interface GaugeNameKey {
        String agentRollupId();
        String gaugeName();
    }
}
