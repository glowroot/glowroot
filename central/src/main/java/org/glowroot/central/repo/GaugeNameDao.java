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

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import org.immutables.value.Value;

import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

class GaugeNameDao {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;
    private final Clock clock;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<GaugeKey> rateLimiter = new RateLimiter<>();

    GaugeNameDao(Session session, ConfigRepositoryImpl configRepository, Clock clock)
            throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.clock = clock;

        int maxRollupHours = configRepository.getCentralStorageConfig().getMaxRollupHours();
        session.createTableWithTWCS("create table if not exists gauge_name (agent_rollup_id"
                + " varchar, capture_time timestamp, gauge_name varchar, primary key"
                + " (agent_rollup_id, capture_time, gauge_name))", maxRollupHours);

        insertPS = session.prepare("insert into gauge_name (agent_rollup_id, capture_time,"
                + " gauge_name) values (?, ?, ?) using ttl ?");
        readPS = session.prepare("select gauge_name from gauge_name where agent_rollup_id = ? and"
                + " capture_time >= ? and capture_time <= ?");
    }

    Set<String> getGaugeNames(String agentRollupId, long from, long to) throws Exception {
        long rolledUpFrom = Utils.getRollupCaptureTime(from, DAYS.toMillis(1));
        long rolledUpTo = Utils.getRollupCaptureTime(to, DAYS.toMillis(1));
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setString(0, agentRollupId);
        boundStatement.setTimestamp(1, new Date(rolledUpFrom));
        boundStatement.setTimestamp(2, new Date(rolledUpTo));
        ResultSet results = session.execute(boundStatement);
        Set<String> gaugeNames = Sets.newHashSet();
        for (Row row : results) {
            gaugeNames.add(checkNotNull(row.getString(0)));
        }
        return gaugeNames;
    }

    List<Future<?>> insert(String agentRollupId, long captureTime, String gaugeName)
            throws Exception {
        long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, DAYS.toMillis(1));
        GaugeKey rateLimiterKey = ImmutableGaugeKey.of(agentRollupId, rollupCaptureTime, gaugeName);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return ImmutableList.of();
        }
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
        boundStatement.setString(i++, gaugeName);
        int maxRollupTTL = configRepository.getCentralStorageConfig().getMaxRollupTTL();
        boundStatement.setInt(i++, Common.getAdjustedTTL(maxRollupTTL, rollupCaptureTime, clock));
        return ImmutableList.of(session.executeAsync(boundStatement));
    }

    @Value.Immutable
    @Styles.AllParameters
    interface GaugeKey {
        String agentRollupId();
        long captureTime();
        String gaugeName();
    }
}
