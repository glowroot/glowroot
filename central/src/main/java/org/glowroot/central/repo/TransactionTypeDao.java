/*
 * Copyright 2015-2023 the original author or authors.
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

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.spotify.futures.CompletableFutures;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.glowroot.central.util.AsyncCache;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.TransactionTypeRepository;
import org.immutables.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionTypeDao implements TransactionTypeRepository {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<TransactionTypeKey> rateLimiter = new RateLimiter<>();

    private final AsyncCache<String, List<String>> transactionTypesCache;

    TransactionTypeDao(Session session, ConfigRepositoryImpl configRepository,
            ClusterManager clusterManager, int targetMaxCentralUiUsers) throws Exception {
        this.session = session;
        this.configRepository = configRepository;

        session.createTableWithLCS("create table if not exists transaction_type (one int,"
                + " agent_rollup varchar, transaction_type varchar, primary key (one, agent_rollup,"
                + " transaction_type))");

        insertPS = session.prepare("insert into transaction_type (one, agent_rollup,"
                + " transaction_type) values (1, ?, ?) using ttl ?");
        readPS = session.prepare(
                "select transaction_type from transaction_type where one = 1 and agent_rollup = ?");

        // this cache is primarily used for calculating Glowroot-Agent-Rollup-Layout-Version, which
        // is performed on the user's current agent-id/agent-rollup-id
        transactionTypesCache = clusterManager.createPerAgentAsyncCache("transactionTypesCache",
                targetMaxCentralUiUsers, new TransactionTypeCacheLoader());
    }

    @Override
    public CompletionStage<List<String>> read(String agentRollupId) {
        return transactionTypesCache.get(agentRollupId);
    }

    @CheckReturnValue
    CompletionStage<?> store(List<String> agentRollups, String transactionType) {
        return configRepository.getCentralStorageConfig().thenCompose(centralStorageConfig -> {
            List<CompletionStage<?>> futures = new ArrayList<>();
            for (String agentRollupId : agentRollups) {
                TransactionTypeKey rateLimiterKey =
                        ImmutableTransactionTypeKey.of(agentRollupId, transactionType);
                if (!rateLimiter.tryAcquire(rateLimiterKey)) {
                    continue;
                }
                CompletionStage<?> future;
                try {
                    int i = 0;
                    BoundStatement boundStatement = insertPS.bind()
                            .setString(i++, agentRollupId)
                            .setString(i++, transactionType)
                            // intentionally not accounting for rateLimiter in TTL
                            .setInt(i++,
                                    centralStorageConfig.getMaxRollupTTL());
                    future = session.writeAsync(boundStatement, CassandraProfile.collector).whenComplete((results, throwable) -> {
                        if (throwable != null) {
                            rateLimiter.release(rateLimiterKey);
                        } else {
                            transactionTypesCache.invalidate(agentRollupId);
                        }
                    });
                } catch (Exception e) {
                    rateLimiter.release(rateLimiterKey);
                    transactionTypesCache.invalidate(agentRollupId);
                    future = CompletableFuture.failedFuture(e);
                }
                futures.add(future);
            }
            return CompletableFutures.allAsList(futures);
        });
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TransactionTypeKey {
        String agentRollupId();
        String transactionType();
    }

    private class TransactionTypeCacheLoader implements AsyncCache.AsyncCacheLoader<String, List<String>> {
        @Override
        public CompletableFuture<List<String>> load(String agentRollupId) {
            BoundStatement boundStatement = readPS.bind()
                .setString(0, agentRollupId);
            List<String> transactionTypes = new ArrayList<>();
            Function<AsyncResultSet, CompletableFuture<List<String>>> compute = new Function<>() {
                @Override
                public CompletableFuture<List<String>> apply(AsyncResultSet asyncResultSet) {
                    for (Row row : asyncResultSet.currentPage()) {
                        transactionTypes.add(checkNotNull(row.getString(0)));
                    }
                    if (asyncResultSet.hasMorePages()) {
                        return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                    }
                    return CompletableFuture.completedFuture(transactionTypes);
                }
            };

            return session.readAsync(boundStatement, CassandraProfile.collector).thenCompose(compute).toCompletableFuture();
        }
    }
}
