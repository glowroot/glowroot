/*
 * Copyright 2015-2018 the original author or authors.
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
import java.util.List;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.util.concurrent.ListenableFuture;
import org.immutables.value.Value;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.repo.TransactionTypeRepository;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionTypeDao implements TransactionTypeRepository {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<TransactionTypeKey> rateLimiter = new RateLimiter<>();

    private final Cache<String, List<String>> transactionTypesCache;

    TransactionTypeDao(Session session, ConfigRepositoryImpl configRepository,
            ClusterManager clusterManager) throws Exception {
        this.session = session;
        this.configRepository = configRepository;

        session.createTableWithLCS("create table if not exists transaction_type (one int,"
                + " agent_rollup varchar, transaction_type varchar, primary key (one, agent_rollup,"
                + " transaction_type))");

        insertPS = session.prepare("insert into transaction_type (one, agent_rollup,"
                + " transaction_type) values (1, ?, ?) using ttl ?");
        readPS = session.prepare(
                "select transaction_type from transaction_type where one = 1 and agent_rollup = ?");

        transactionTypesCache = clusterManager.createCache("transactionTypesCache",
                new TransactionTypeCacheLoader());
    }

    @Override
    public List<String> read(String agentRollupId) throws Exception {
        return transactionTypesCache.get(agentRollupId);
    }

    List<Future<?>> store(List<String> agentRollups, String transactionType) throws Exception {
        List<Future<?>> futures = new ArrayList<>();
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
                transactionTypesCache.invalidate(agentRollupId);
                throw e;
            }
            futures.add(MoreFutures.onSuccessAndFailure(future,
                    () -> transactionTypesCache.invalidate(agentRollupId),
                    () -> rateLimiter.invalidate(rateLimiterKey)));
        }
        return futures;
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TransactionTypeKey {
        String agentRollupId();
        String transactionType();
    }

    private class TransactionTypeCacheLoader implements CacheLoader<String, List<String>> {
        @Override
        public List<String> load(String agentRollupId) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, agentRollupId);
            ResultSet results = session.execute(boundStatement);
            List<String> transactionTypes = new ArrayList<>();
            for (Row row : results) {
                transactionTypes.add(checkNotNull(row.getString(0)));
            }
            return transactionTypes;
        }
    }
}
