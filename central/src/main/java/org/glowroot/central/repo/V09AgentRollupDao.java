/*
 * Copyright 2017-2023 the original author or authors.
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.datastax.oss.driver.api.core.cql.*;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.central.util.AsyncCache;
import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common2.repo.CassandraProfile;

import static com.google.common.base.Preconditions.checkNotNull;

// this is needed as long as new v09 agents may connect in the future
public class V09AgentRollupDao {

    private static final String SINGLE_CACHE_KEY = "x";

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final AsyncCache<String, Map<String, String>> agentRollupIdsCache;

    V09AgentRollupDao(Session session, ClusterManager clusterManager) throws Exception {
        this.session = session;

        session.createTableWithLCS("create table if not exists v09_agent_rollup (one int,"
                + " v09_agent_id varchar, v09_agent_rollup_id varchar, primary key (one,"
                + " v09_agent_id))");

        insertPS = session.prepare("insert into v09_agent_rollup (one, v09_agent_id,"
                + " v09_agent_rollup_id) values (1, ?, ?)");
        readPS = session.prepare(
                "select v09_agent_id, v09_agent_rollup_id from v09_agent_rollup where one = 1");

        agentRollupIdsCache = clusterManager.createSelfBoundedAsyncCache("v09AgentRollupIdCache",
                new AgentRollupIdCacheLoader());
    }

    public void store(String v09AgentId, String v09AgentRollupId) throws Exception {
        int i = 0;
        BoundStatement boundStatement = insertPS.bind()
            .setString(i++, v09AgentId)
            .setString(i++, v09AgentRollupId);
        session.writeAsync(boundStatement, CassandraProfile.collector).thenRun(() -> {
            agentRollupIdsCache.invalidate(SINGLE_CACHE_KEY);
        }).toCompletableFuture().get();
    }

    public @Nullable String getV09AgentRollupId(String v09AgentId) throws Exception {
        return agentRollupIdsCache.get(SINGLE_CACHE_KEY).toCompletableFuture().get().get(v09AgentId);
    }

    private class AgentRollupIdCacheLoader implements AsyncCache.AsyncCacheLoader<String, Map<String, String>> {
        @Override
        public CompletableFuture<Map<String, String>> load(String key) {
            BoundStatement boundStatement = readPS.bind();
            Map<String, String> agentRollupIds = new HashMap<>();
            Function<AsyncResultSet, CompletableFuture<Map<String, String>>> compute = new Function<>() {

                @Override
                public CompletableFuture<Map<String, String>> apply(AsyncResultSet results) {
                    for (Row row : results.currentPage()) {
                        int i = 0;
                        String v09AgentId = checkNotNull(row.getString(i++));
                        String v09AgentRollupId = checkNotNull(row.getString(i++));
                        agentRollupIds.put(v09AgentId, v09AgentRollupId);
                    }
                    if (results.hasMorePages()) {
                        return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                    }
                    return CompletableFuture.completedFuture(agentRollupIds);
                }
            };
            return session.readAsync(boundStatement, CassandraProfile.collector).thenCompose(compute).toCompletableFuture();
        }
    }
}
