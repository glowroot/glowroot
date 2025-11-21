/*
 * Copyright 2016-2023 the original author or authors.
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
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.primitives.Ints;
import org.glowroot.central.util.AsyncCache;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.TraceAttributeNameRepository;
import org.immutables.value.Value;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

class TraceAttributeNameDao implements TraceAttributeNameRepository {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<TraceAttributeNameKey> rateLimiter = new RateLimiter<>();
    private final AsyncCache<String, Map<String, List<String>>> traceAttributeNamesCache;

    TraceAttributeNameDao(Session session, ConfigRepositoryImpl configRepository,
                          ClusterManager clusterManager, int targetMaxCentralUiUsers) throws Exception {
        this.session = session;
        this.configRepository = configRepository;

        session.createTableWithLCS("create table if not exists trace_attribute_name (agent_rollup"
                + " varchar, transaction_type varchar, trace_attribute_name varchar, primary key"
                + " (agent_rollup, transaction_type, trace_attribute_name))");

        insertPS = session.prepare("insert into trace_attribute_name (agent_rollup,"
                + " transaction_type, trace_attribute_name) values (?, ?, ?) using ttl ?");
        readPS = session.prepare("select transaction_type, trace_attribute_name from"
                + " trace_attribute_name where agent_rollup = ?");

        // this cache is primarily used for calculating Glowroot-Agent-Rollup-Layout-Version, which
        // is performed on the user's current agent-id/agent-rollup-id
        traceAttributeNamesCache = clusterManager.createPerAgentAsyncCache("traceAttributeNamesCache",
                targetMaxCentralUiUsers, new TraceAttributeNameCacheLoader());
    }

    @Override
    public Map<String, List<String>> read(String agentRollupId) throws Exception {
        return traceAttributeNamesCache.get(agentRollupId).get();
    }

    CompletionStage<?> store(String agentRollupId, String transactionType, String traceAttributeName) {
        TraceAttributeNameKey rateLimiterKey = ImmutableTraceAttributeNameKey.of(agentRollupId,
                transactionType, traceAttributeName);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return CompletableFuture.completedFuture(null);
        }
        return getTraceTTL().thenCompose(ttl -> {
            int i = 0;
            BoundStatement boundStatement = insertPS.bind()
                    .setString(i++, agentRollupId)
                    .setString(i++, transactionType)
                    .setString(i++, traceAttributeName)
                    .setInt(i++, ttl);
            return session.writeAsync(boundStatement, CassandraProfile.collector).whenComplete(((asyncResultSet, throwable) -> {
                if (throwable != null) {
                    rateLimiter.release(rateLimiterKey);
                } else {
                    traceAttributeNamesCache.invalidate(agentRollupId);
                }
            }));
        });
    }

    private CompletionStage<Integer> getTraceTTL() {
        return configRepository.getCentralStorageConfig().thenApply(centralStorageConfig -> {
            int ttl = centralStorageConfig.getTraceTTL();
            if (ttl == 0) {
                return 0;
            }
            // adding 1 day to account for rateLimiter
            return Ints.saturatedCast(ttl + DAYS.toSeconds(1));
        });
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TraceAttributeNameKey {
        String agentRollupId();

        String transactionType();

        String traceAttributeName();
    }

    private class TraceAttributeNameCacheLoader
            implements AsyncCache.AsyncCacheLoader<String, Map<String, List<String>>> {
        @Override
        public CompletableFuture<Map<String, List<String>>> load(String agentRollupId) {
            BoundStatement boundStatement = readPS.bind()
                    .setString(0, agentRollupId);
            ListMultimap<String, String> traceAttributeNames = ArrayListMultimap.create();
            Function<AsyncResultSet, CompletableFuture<Map<String, List<String>>>> compute = new Function<>() {
                @Override
                public CompletableFuture<Map<String, List<String>>> apply(AsyncResultSet results) {
                    for (Row row : results.currentPage()) {
                        int i = 0;
                        String transactionType = checkNotNull(row.getString(i++));
                        String traceAttributeName = checkNotNull(row.getString(i++));
                        traceAttributeNames.put(transactionType, traceAttributeName);
                    }
                    if (results.hasMorePages()) {
                        return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                    }
                    return CompletableFuture.completedFuture(Multimaps.asMap(traceAttributeNames));
                }
            };
            return session.readAsync(boundStatement, CassandraProfile.collector).thenCompose(compute).toCompletableFuture();
        }
    }
}
