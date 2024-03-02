/*
 * Copyright 2019-2023 the original author or authors.
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
import java.util.ListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import com.datastax.oss.driver.api.core.cql.*;
import com.google.common.base.Joiner;

import org.glowroot.central.util.AsyncCache;
import org.glowroot.central.util.AsyncCache.AsyncCacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.MoreConfigDefaults;
import org.glowroot.common2.repo.AgentDisplayRepository;
import org.glowroot.common2.repo.CassandraProfile;

import static com.google.common.base.Preconditions.checkNotNull;

// this is just a read-optimization store of the same data in agent_config
// TODO agent display records never expire for abandoned agent rollup ids
public class AgentDisplayDao implements AgentDisplayRepository {

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement deletePS;
    private final PreparedStatement readPS;

    private final AsyncCache<String, String> agentDisplayCache;

    private final Executor asyncExecutor;

    AgentDisplayDao(Session session, ClusterManager clusterManager, Executor asyncExecutor,
            int targetMaxActiveAgentsInPast7Days) throws Exception {
        this.session = session;
        this.asyncExecutor = asyncExecutor;

        session.createTableWithLCS("create table if not exists agent_display (agent_rollup_id"
                + " varchar, display varchar, primary key (agent_rollup_id))");

        insertPS = session.prepare(
                "insert into agent_display (agent_rollup_id, display) values (?, ?) using ttl ?");
        deletePS = session.prepare("delete from agent_display where agent_rollup_id = ?");
        readPS = session.prepare("select display from agent_display where agent_rollup_id = ?");

        agentDisplayCache = clusterManager.createPerAgentAsyncCache("agentDisplayCache",
                targetMaxActiveAgentsInPast7Days * 10, new AgentDisplayCacheLoader());
    }

    void store(String agentRollupId, String display) throws Exception {
        CompletionStage<AsyncResultSet> ret;
        if (display.isEmpty()) {
            BoundStatement boundStatement = deletePS.bind()
                .setString(0, agentRollupId);
            ret = session.writeAsync(boundStatement, CassandraProfile.collector);
        } else {
            BoundStatement boundStatement = insertPS.bind()
                .setString(0, agentRollupId)
                .setString(1, display);
            ret = session.writeAsync(boundStatement, CassandraProfile.collector);
        }
        ret.thenRun(() -> agentDisplayCache.invalidate(agentRollupId)).toCompletableFuture().get();
    }

    @Override
    public String readFullDisplay(String agentRollupId) throws Exception {
        return Joiner.on(" :: ").join(readDisplayParts(agentRollupId));
    }

    @Override
    public List<String> readDisplayParts(String agentRollupId) throws Exception {
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentRollupId);
        List<String> displayParts = new ArrayList<>();
        for (ListIterator<String> i = agentRollupIds.listIterator(agentRollupIds.size()); i
                .hasPrevious();) {
            displayParts.add(readLastDisplayPartAsync(i.previous()).get());
        }
        return displayParts;
    }

    @Override
    public CompletableFuture<String> readLastDisplayPartAsync(String agentRollupId) {
        return agentDisplayCache.get(agentRollupId);
    }

    private class AgentDisplayCacheLoader implements AsyncCacheLoader<String, String> {
        @Override
        public CompletableFuture<String> load(String agentRollupId) {
            BoundStatement boundStatement = readPS.bind()
                .setString(0, agentRollupId);
            return session.readAsync(boundStatement, CassandraProfile.web)
                    .thenApplyAsync(results -> {
                        Row row = results.one();
                        if (row == null) {
                            return MoreConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId);
                        }
                        String display = checkNotNull(row.getString(0));
                        if (display.isEmpty()) {
                            return MoreConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId);
                        }
                        return display;
                    }, asyncExecutor)
                    .toCompletableFuture();
        }
    }
}
