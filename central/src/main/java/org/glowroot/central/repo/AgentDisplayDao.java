/*
 * Copyright 2019 the original author or authors.
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
import java.util.concurrent.Executor;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.glowroot.central.util.AsyncCache;
import org.glowroot.central.util.AsyncCache.AsyncCacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.MoreConfigDefaults;
import org.glowroot.common2.repo.AgentDisplayRepository;

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
        if (display.isEmpty()) {
            BoundStatement boundStatement = deletePS.bind();
            boundStatement.setString(0, agentRollupId);
            session.write(boundStatement);
        } else {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, agentRollupId);
            boundStatement.setString(1, display);
            session.write(boundStatement);
        }
        agentDisplayCache.invalidate(agentRollupId);
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
    public ListenableFuture<String> readLastDisplayPartAsync(String agentRollupId)
            throws Exception {
        return agentDisplayCache.get(agentRollupId);
    }

    private class AgentDisplayCacheLoader implements AsyncCacheLoader<String, String> {
        @Override
        public ListenableFuture<String> load(String agentRollupId) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, agentRollupId);
            ListenableFuture<ResultSet> future = session.readAsync(boundStatement);
            return Futures.transform(future, new Function<ResultSet, String>() {
                @Override
                public String apply(ResultSet results) {
                    Row row = results.one();
                    if (row == null) {
                        return MoreConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId);
                    }
                    String display = checkNotNull(row.getString(0));
                    if (display.isEmpty()) {
                        return MoreConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId);
                    }
                    return display;
                }
            }, asyncExecutor);
        }
    }
}
