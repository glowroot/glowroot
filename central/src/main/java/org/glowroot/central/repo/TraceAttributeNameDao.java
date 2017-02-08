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

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.MoreExecutors;
import org.immutables.value.Value;

import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.TraceAttributeNameRepository;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

public class TraceAttributeNameDao implements TraceAttributeNameRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final String SINGLE_CACHE_KEY = "x";

    private final Session session;
    private final ConfigRepository configRepository;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<TraceAttributeNameKey> rateLimiter = new RateLimiter<>();

    private final LoadingCache<String, Map<String, Map<String, List<String>>>> cache =
            CacheBuilder.newBuilder().build(new TraceAttributeNameCacheLoader());

    public TraceAttributeNameDao(Session session, ConfigRepository configRepository) {
        this.session = session;
        this.configRepository = configRepository;

        session.execute("create table if not exists trace_attribute_name (agent_rollup varchar,"
                + " transaction_type varchar, trace_attribute_name varchar, primary key"
                + " ((agent_rollup, transaction_type), trace_attribute_name)) " + WITH_LCS);

        insertPS = session.prepare("insert into trace_attribute_name (agent_rollup,"
                + " transaction_type, trace_attribute_name) values (?, ?, ?) using ttl ?");
        readPS = session.prepare("select agent_rollup, transaction_type, trace_attribute_name"
                + " from trace_attribute_name");
    }

    @Override
    public Map<String, Map<String, List<String>>> read() throws Exception {
        return cache.get(SINGLE_CACHE_KEY);
    }

    void store(String agentRollupId, String transactionType, String traceAttributeName,
            List<ResultSetFuture> futures) throws Exception {
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
        boundStatement.setInt(i++, getMaxTTL());
        ResultSetFuture future = Sessions.executeAsyncWithOnFailure(session, boundStatement,
                () -> rateLimiter.invalidate(rateLimiterKey));
        future.addListener(() -> cache.invalidate(SINGLE_CACHE_KEY),
                MoreExecutors.directExecutor());
        futures.add(future);
        cache.invalidate(SINGLE_CACHE_KEY);
    }

    private int getMaxTTL() throws Exception {
        long maxTTL = 0;
        for (long expirationHours : configRepository.getStorageConfig().rollupExpirationHours()) {
            if (expirationHours == 0) {
                // zero value expiration/TTL means never expire
                return 0;
            }
            maxTTL = Math.max(maxTTL, HOURS.toSeconds(expirationHours));
        }
        // adding 1 day to account for rateLimiter
        return Ints.saturatedCast(maxTTL + DAYS.toSeconds(1));
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TraceAttributeNameKey {
        String agentRollupId();
        String transactionType();
        String traceAttributeName();
    }

    private class TraceAttributeNameCacheLoader
            extends CacheLoader<String, Map<String, Map<String, List<String>>>> {
        @Override
        public Map<String, Map<String, List<String>>> load(String key) {
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
