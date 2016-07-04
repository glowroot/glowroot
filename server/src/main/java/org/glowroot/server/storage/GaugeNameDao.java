/*
 * Copyright 2015-2016 the original author or authors.
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

import java.util.List;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.RateLimiter;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.ConfigRepository;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

class GaugeNameDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final ConfigRepository configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    // 2-day expiration is just to periodically clean up cache
    private final LoadingCache<GaugeNameKey, RateLimiter> rateLimiters = CacheBuilder.newBuilder()
            .expireAfterAccess(2, DAYS)
            .build(new CacheLoader<GaugeNameKey, RateLimiter>() {
                @Override
                public RateLimiter load(GaugeNameKey key) throws Exception {
                    // 1 permit per 24 hours
                    return RateLimiter.create(1 / (24 * 3600.0));
                }
            });

    GaugeNameDao(Session session, ConfigRepository configRepository) {
        this.session = session;
        this.configRepository = configRepository;

        session.execute("create table if not exists gauge_name (agent_rollup varchar,"
                + " gauge_name varchar, primary key (agent_rollup, gauge_name)) " + WITH_LCS);

        insertPS = session.prepare("insert into gauge_name (agent_rollup, gauge_name)"
                + " values (?, ?) using ttl ?");
        readPS = session.prepare("select gauge_name from gauge_name where agent_rollup = ?");
    }

    List<String> getGaugeNames(String agentRollup) {
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setString(0, agentRollup);
        ResultSet results = session.execute(boundStatement);
        List<String> gaugeNames = Lists.newArrayList();
        for (Row row : results) {
            gaugeNames.add(checkNotNull(row.getString(0)));
        }
        return gaugeNames;
    }

    void maybeUpdateLastCaptureTime(String agentRollup, String gaugeName,
            List<ResultSetFuture> futures) {
        RateLimiter rateLimiter =
                rateLimiters.getUnchecked(ImmutableGaugeNameKey.of(agentRollup, gaugeName));
        if (!rateLimiter.tryAcquire()) {
            return;
        }
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setInt(i++, getMaxTTL());
        futures.add(session.executeAsync(boundStatement));
    }

    private int getMaxTTL() {
        long maxTTL = 0;
        for (long expirationHours : configRepository.getStorageConfig().rollupExpirationHours()) {
            maxTTL = Math.max(maxTTL, HOURS.toSeconds(expirationHours));
        }
        return Ints.saturatedCast(maxTTL);
    }

    @Value.Immutable
    @Styles.AllParameters
    interface GaugeNameKey {
        String agentRollup();
        String gaugeName();
    }
}
