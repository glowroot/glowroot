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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import org.immutables.value.Value;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;

class GaugeNameDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final ConfigRepositoryImpl configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<GaugeNameKey> rateLimiter = new RateLimiter<>();

    private final Cache<String, List<String>> gaugeNamesCache;

    GaugeNameDao(Session session, ConfigRepositoryImpl configRepository,
            ClusterManager clusterManager) throws Exception {
        this.session = session;
        this.configRepository = configRepository;

        session.execute("create table if not exists gauge_name (agent_rollup varchar,"
                + " gauge_name varchar, primary key (agent_rollup, gauge_name)) " + WITH_LCS);

        insertPS = session.prepare("insert into gauge_name (agent_rollup, gauge_name)"
                + " values (?, ?) using ttl ?");
        readPS = session.prepare("select gauge_name from gauge_name where agent_rollup = ?");

        gaugeNamesCache = clusterManager.createCache("gaugeNamesCache", new GaugeNameCacheLoader());
    }

    List<String> getGaugeNames(String agentRollupId) throws Exception {
        return gaugeNamesCache.get(agentRollupId);
    }

    List<Future<?>> store(String agentRollupId, String gaugeName) throws Exception {
        GaugeNameKey rateLimiterKey = ImmutableGaugeNameKey.of(agentRollupId, gaugeName);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return ImmutableList.of();
        }
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, gaugeName);
        // intentionally not accounting for rateLimiter in TTL
        boundStatement.setInt(i++, configRepository.getCentralStorageConfig().getMaxRollupTTL());
        ListenableFuture<ResultSet> future = session.executeAsync(boundStatement);
        CompletableFuture<?> chainedFuture =
                MoreFutures.onFailure(future, () -> gaugeNamesCache.invalidate(agentRollupId));
        return ImmutableList.of(chainedFuture);
    }

    @Value.Immutable
    @Styles.AllParameters
    interface GaugeNameKey {
        String agentRollupId();
        String gaugeName();
    }

    private class GaugeNameCacheLoader implements CacheLoader<String, List<String>> {
        @Override
        public List<String> load(String agentRollupId) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, agentRollupId);
            ResultSet results = session.execute(boundStatement);
            List<String> gaugeNames = Lists.newArrayList();
            for (Row row : results) {
                gaugeNames.add(checkNotNull(row.getString(0)));
            }
            return gaugeNames;
        }
    }
}
