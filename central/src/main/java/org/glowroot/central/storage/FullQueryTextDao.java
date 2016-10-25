/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central.storage;

import java.util.List;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import org.immutables.value.Value;

import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class FullQueryTextDao {

    private final Session session;
    private final ConfigRepository configRepository;

    private final PreparedStatement insertCheckPS;
    private final PreparedStatement readCheckPS;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<FullQueryTextKey> rateLimiter = new RateLimiter<>(10000);

    public FullQueryTextDao(Session session, ConfigRepository configRepository) {
        this.session = session;
        this.configRepository = configRepository;

        // intentionally using default size-tiered compaction strategy
        session.execute("create table if not exists full_query_text_check (agent_rollup varchar,"
                + " full_query_text_sha1 varchar, primary key (agent_rollup,"
                + " full_query_text_sha1))");
        session.execute("create table if not exists full_query_text (full_query_text_sha1 varchar,"
                + " full_query_text varchar, primary key (full_query_text_sha1))");

        insertCheckPS = session.prepare("insert into full_query_text_check (agent_rollup,"
                + " full_query_text_sha1) values (?, ?) using ttl ?");
        readCheckPS = session.prepare("select agent_rollup from full_query_text_check"
                + " where agent_rollup = ? and full_query_text_sha1 = ?");

        insertPS = session.prepare("insert into full_query_text (full_query_text_sha1,"
                + " full_query_text) values (?, ?) using ttl ?");
        readPS = session.prepare(
                "select full_query_text from full_query_text where full_query_text_sha1 = ?");
    }

    @Nullable
    String getFullText(String agentRollup, String fullTextSha1) {
        BoundStatement boundStatement = readCheckPS.bind();
        boundStatement.setString(0, agentRollup);
        boundStatement.setString(1, fullTextSha1);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            return null;
        }
        boundStatement = readPS.bind();
        boundStatement.setString(0, fullTextSha1);
        results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        return row.getString(0);
    }

    List<ResultSetFuture> store(String agentRollup, String fullTextSha1, String fullText) {
        FullQueryTextKey rateLimiterKey = ImmutableFullQueryTextKey.of(agentRollup, fullTextSha1);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return ImmutableList.of();
        }
        return storeInternal(rateLimiterKey, fullText);
    }

    List<ResultSetFuture> updateTTL(String agentRollup, String fullTextSha1) {
        FullQueryTextKey rateLimiterKey = ImmutableFullQueryTextKey.of(agentRollup, fullTextSha1);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return ImmutableList.of();
        }
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setString(0, fullTextSha1);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        String fullText = checkNotNull(checkNotNull(row).getString(0));
        return storeInternal(rateLimiterKey, fullText);
    }

    List<ResultSetFuture> updateCheckTTL(String agentRollup, String fullTextSha1) {
        FullQueryTextKey rateLimiterKey = ImmutableFullQueryTextKey.of(agentRollup, fullTextSha1);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return ImmutableList.of();
        }
        List<ResultSetFuture> futures = Lists.newArrayList();
        storeCheckInternal(rateLimiterKey);
        return futures;
    }

    private List<ResultSetFuture> storeInternal(FullQueryTextKey rateLimiterKey, String fullText) {
        List<ResultSetFuture> futures = Lists.newArrayList();
        futures.add(storeCheckInternal(rateLimiterKey));
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, rateLimiterKey.fullTextSha1());
        boundStatement.setString(i++, fullText);
        boundStatement.setInt(i++, getTTL());
        futures.add(Sessions.executeAsyncWithOnFailure(session, boundStatement,
                () -> rateLimiter.invalidate(rateLimiterKey)));
        return futures;
    }

    private ResultSetFuture storeCheckInternal(FullQueryTextKey rateLimiterKey) {
        BoundStatement boundStatement = insertCheckPS.bind();
        int i = 0;
        boundStatement.setString(i++, rateLimiterKey.agentRollup());
        boundStatement.setString(i++, rateLimiterKey.fullTextSha1());
        boundStatement.setInt(i++, getTTL());
        return Sessions.executeAsyncWithOnFailure(session, boundStatement,
                () -> rateLimiter.invalidate(rateLimiterKey));
    }

    private int getTTL() {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        RollupConfig lastRollupConfig = rollupConfigs.get(rollupConfigs.size() - 1);
        // adding largest rollup time to account for query being retained longer by rollups
        long ttl = MILLISECONDS.toSeconds(lastRollupConfig.intervalMillis())
                // adding 1 day to account for rateLimiter
                + DAYS.toSeconds(1)
                + HOURS.toSeconds(
                        configRepository.getStorageConfig().fullQueryTextExpirationHours());
        return Ints.saturatedCast(ttl);
    }

    @Value.Immutable
    @Styles.AllParameters
    interface FullQueryTextKey {
        String agentRollup();
        String fullTextSha1();
    }
}
