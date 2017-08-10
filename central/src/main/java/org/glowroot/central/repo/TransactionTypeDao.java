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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import org.immutables.value.Value;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.repo.TransactionTypeRepository;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionTypeDao implements TransactionTypeRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final String SINGLE_CACHE_KEY = "x";

    private final Session session;
    private final ConfigRepositoryImpl configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<TransactionTypeKey> rateLimiter = new RateLimiter<>();

    private final Cache<String, Map<String, List<String>>> transactionTypesCache;

    TransactionTypeDao(Session session, ConfigRepositoryImpl configRepository,
            ClusterManager clusterManager) throws Exception {
        this.session = session;
        this.configRepository = configRepository;

        session.execute("create table if not exists transaction_type (one int,"
                + " agent_rollup varchar, transaction_type varchar, primary key"
                + " (one, agent_rollup, transaction_type)) " + WITH_LCS);

        insertPS = session.prepare("insert into transaction_type (one, agent_rollup,"
                + " transaction_type) values (1, ?, ?) using ttl ?");
        readPS = session.prepare(
                "select agent_rollup, transaction_type from transaction_type where one = 1");

        transactionTypesCache = clusterManager.createCache("transactionTypesCache",
                new TransactionTypeCacheLoader());
    }

    @Override
    public Map<String, List<String>> read() throws Exception {
        return transactionTypesCache.get(SINGLE_CACHE_KEY);
    }

    List<Future<?>> store(List<String> agentRollups, String transactionType) throws Exception {
        List<Future<?>> futures = Lists.newArrayList();
        for (String agentRollupId : agentRollups) {
            TransactionTypeKey rateLimiterKey =
                    ImmutableTransactionTypeKey.of(agentRollupId, transactionType);
            if (!rateLimiter.tryAcquire(rateLimiterKey)) {
                continue;
            }
            ListenableFuture<ResultSet> future;
            try {
                BoundStatement boundStatement = insertPS.bind();
                int i = 0;
                boundStatement.setString(i++, agentRollupId);
                boundStatement.setString(i++, transactionType);
                // intentionally not accounting for rateLimiter in TTL
                boundStatement.setInt(i++,
                        configRepository.getCentralStorageConfig().getMaxRollupTTL());
                future = session.executeAsync(boundStatement);
            } catch (Exception e) {
                rateLimiter.invalidate(rateLimiterKey);
                transactionTypesCache.invalidate(SINGLE_CACHE_KEY);
                throw e;
            }
            CompletableFuture<?> chainedFuture =
                    MoreFutures.onFailure(future, () -> rateLimiter.invalidate(rateLimiterKey));
            chainedFuture = chainedFuture.whenComplete(
                    (result, t) -> transactionTypesCache.invalidate(SINGLE_CACHE_KEY));
            futures.add(chainedFuture);
        }
        return futures;
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TransactionTypeKey {
        String agentRollupId();
        String transactionType();
    }

    private class TransactionTypeCacheLoader
            implements CacheLoader<String, Map<String, List<String>>> {
        @Override
        public Map<String, List<String>> load(String key) throws Exception {
            ResultSet results = session.execute(readPS.bind());

            ImmutableMap.Builder<String, List<String>> builder = ImmutableMap.builder();
            String currAgentRollup = null;
            List<String> currTransactionTypes = Lists.newArrayList();
            for (Row row : results) {
                String agentRollupId = checkNotNull(row.getString(0));
                String transactionType = checkNotNull(row.getString(1));
                if (currAgentRollup == null) {
                    currAgentRollup = agentRollupId;
                }
                if (!agentRollupId.equals(currAgentRollup)) {
                    builder.put(currAgentRollup, ImmutableList.copyOf(currTransactionTypes));
                    currAgentRollup = agentRollupId;
                    currTransactionTypes = Lists.newArrayList();
                }
                currTransactionTypes.add(transactionType);
            }
            if (currAgentRollup != null) {
                builder.put(currAgentRollup, ImmutableList.copyOf(currTransactionTypes));
            }
            return builder.build();
        }
    }
}
