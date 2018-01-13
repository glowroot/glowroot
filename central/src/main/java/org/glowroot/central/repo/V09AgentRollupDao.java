/*
 * Copyright 2017-2018 the original author or authors.
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

import java.util.Map;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.Maps;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;

import static com.google.common.base.Preconditions.checkNotNull;

// this is needed as long as new v09 agents may connect in the future
public class V09AgentRollupDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final String SINGLE_CACHE_KEY = "x";

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final Cache<String, Map<String, String>> agentRollupIdsCache;

    V09AgentRollupDao(Session session, ClusterManager clusterManager) throws Exception {
        this.session = session;

        session.execute("create table if not exists v09_agent_rollup (one int, v09_agent_id"
                + " varchar, v09_agent_rollup_id varchar, primary key (one, v09_agent_id,"
                + " v09_agent_rollup_id)) " + WITH_LCS);

        insertPS = session.prepare("insert into v09_agent_rollup (one, v09_agent_id,"
                + " v09_agent_rollup_id) values (1, ?, ?)");
        readPS = session.prepare(
                "select v09_agent_id, v09_agent_rollup_id from v09_agent_rollup where one = 1");

        agentRollupIdsCache =
                clusterManager.createCache("v09AgentRollupIdCache", new AgentRollupIdCacheLoader());
    }

    public void store(String v09AgentId, String v09AgentRollupId) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, v09AgentId);
        boundStatement.setString(i++, v09AgentRollupId);
        session.execute(boundStatement);
        agentRollupIdsCache.invalidate(SINGLE_CACHE_KEY);
    }

    public @Nullable String getV09AgentRollupId(String v09AgentId) throws Exception {
        return agentRollupIdsCache.get(SINGLE_CACHE_KEY).get(v09AgentId);
    }

    private class AgentRollupIdCacheLoader implements CacheLoader<String, Map<String, String>> {
        @Override
        public Map<String, String> load(String key) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            ResultSet results = session.execute(boundStatement);
            Map<String, String> agentRollupIds = Maps.newHashMap();
            for (Row row : results) {
                int i = 0;
                String v09AgentId = checkNotNull(row.getString(i++));
                String v09AgentRollupId = checkNotNull(row.getString(i++));
                agentRollupIds.put(v09AgentId, v09AgentRollupId);
            }
            return agentRollupIds;
        }
    }
}
