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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import org.immutables.value.Value;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.repo.TraceAttributeNameRepository;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

class TraceAttributeNameDao implements TraceAttributeNameRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final String SINGLE_CACHE_KEY = "x";

    private final Session session;
    private final ConfigRepositoryImpl configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<TraceAttributeNameKey> rateLimiter = new RateLimiter<>();

    private final Cache<String, Map<String, Map<String, List<String>>>> traceAttributeNamesCache;

    TraceAttributeNameDao(Session session, ConfigRepositoryImpl configRepository,
            ClusterManager clusterManager) throws Exception {
        this.session = session;
        this.configRepository = configRepository;

        session.execute("create table if not exists trace_attribute_name"
                + " (agent_rollup varchar, transaction_type varchar, trace_attribute_name varchar,"
                + " primary key ((agent_rollup, transaction_type), trace_attribute_name)) "
                + WITH_LCS);

        insertPS = session.prepare("insert into trace_attribute_name (agent_rollup,"
                + " transaction_type, trace_attribute_name) values (?, ?, ?) using ttl ?");
        readPS = session.prepare("select agent_rollup, transaction_type, trace_attribute_name"
                + " from trace_attribute_name");

        traceAttributeNamesCache = clusterManager.createCache("traceAttributeNamesCache",
                new TraceAttributeNameCacheLoader());
    }

    @Override
    public Map<String, Map<String, List<String>>> read() throws Exception {
        return traceAttributeNamesCache.get(SINGLE_CACHE_KEY);
    }

    void store(String agentRollupId, String transactionType, String traceAttributeName,
            List<Future<?>> futures) throws Exception {
        TraceAttributeNameKey rateLimiterKey = ImmutableTraceAttributeNameKey.of(agentRollupId,
                transactionType, traceAttributeName);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return;
        }
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, transactionType);
        boundStatement.setString(i++, traceAttributeName);
        boundStatement.setInt(i++, getTraceTTL());
        ListenableFuture<ResultSet> future = session.executeAsync(boundStatement);
        CompletableFuture<?> chainedFuture =
                MoreFutures.onFailure(future, () -> rateLimiter.invalidate(rateLimiterKey));
        chainedFuture = chainedFuture
                .whenComplete((result, t) -> traceAttributeNamesCache.invalidate(SINGLE_CACHE_KEY));
        futures.add(chainedFuture);
    }

    private int getTraceTTL() throws Exception {
        int ttl = configRepository.getCentralStorageConfig().getTraceTTL();
        if (ttl == 0) {
            return 0;
        }
        // adding 1 day to account for rateLimiter
        return Ints.saturatedCast(ttl + DAYS.toSeconds(1));
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TraceAttributeNameKey {
        String agentRollupId();
        String transactionType();
        String traceAttributeName();
    }

    private class TraceAttributeNameCacheLoader
            implements CacheLoader<String, Map<String, Map<String, List<String>>>> {
        @Override
        public Map<String, Map<String, List<String>>> load(String key) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            ResultSet results = session.execute(boundStatement);
            Map<String, Map<String, List<String>>> traceAttributeNames = Maps.newHashMap();
            for (Row row : results) {
                int i = 0;
                String agentRollup = checkNotNull(row.getString(i++));
                String transactionType = checkNotNull(row.getString(i++));
                String traceAttributeName = checkNotNull(row.getString(i++));
                Map<String, List<String>> innerMap =
                        traceAttributeNames.computeIfAbsent(agentRollup, k -> new HashMap<>());
                innerMap.computeIfAbsent(transactionType, k -> new ArrayList<>())
                        .add(traceAttributeName);
            }
            return traceAttributeNames;
        }
    }
}
