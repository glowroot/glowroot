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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.datastax.oss.driver.api.core.cql.*;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class FullQueryTextDao implements AutoCloseable {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;
    private final Executor asyncExecutor;

    private final PreparedStatement insertCheckV2PS;
    private final PreparedStatement readCheckV2PS;
    private final PreparedStatement readCheckV1PS;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;
    private final PreparedStatement readTtlPS;

    private final RateLimiter<String> rateLimiter = new RateLimiter<>(100000, true);

    FullQueryTextDao(Session session, ConfigRepositoryImpl configRepository, Executor asyncExecutor)
            throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.asyncExecutor = asyncExecutor;

        session.createTableWithSTCS("create table if not exists full_query_text_check (agent_rollup"
                + " varchar, full_query_text_sha1 varchar, primary key (agent_rollup,"
                + " full_query_text_sha1))");
        session.createTableWithSTCS("create table if not exists full_query_text_check_v2"
                + " (agent_rollup varchar, full_query_text_sha1 varchar, primary key"
                + " ((agent_rollup, full_query_text_sha1)))");
        session.createTableWithSTCS("create table if not exists full_query_text"
                + " (full_query_text_sha1 varchar, full_query_text varchar, primary key"
                + " (full_query_text_sha1))");

        insertCheckV2PS = session.prepare("insert into full_query_text_check_v2 (agent_rollup,"
                + " full_query_text_sha1) values (?, ?) using ttl ?");
        readCheckV2PS = session.prepare("select agent_rollup from full_query_text_check_v2 where"
                + " agent_rollup = ? and full_query_text_sha1 = ?");
        readCheckV1PS = session.prepare("select agent_rollup from full_query_text_check where"
                + " agent_rollup = ? and full_query_text_sha1 = ?");

        insertPS = session.prepare("insert into full_query_text (full_query_text_sha1,"
                + " full_query_text) values (?, ?) using ttl ?");
        readPS = session.prepare(
                "select full_query_text from full_query_text where full_query_text_sha1 = ?");
        readTtlPS = session.prepare(
                "select TTL(full_query_text) from full_query_text where full_query_text_sha1 = ?");

        MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
        platformMBeanServer.registerMBean(rateLimiter.getLocalCacheStats(), ObjectName
                .getInstance("org.glowroot.central:type=FullQueryTextRateLimiter"));
    }

    @Nullable
    String getFullText(String agentRollupId, String fullTextSha1) throws Exception {
        String fullText = getFullTextUsingPS(agentRollupId, fullTextSha1, readCheckV2PS);
        if (fullText != null) {
            return fullText;
        }
        return getFullTextUsingPS(agentRollupId, fullTextSha1, readCheckV1PS);
    }

    List<CompletableFuture<?>> store(List<String> agentRollupIds, String fullTextSha1, String fullText) {
        // relying on agent side to rate limit (re-)sending the same full text
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (String agentRollupId : agentRollupIds) {
            int i = 0;
            BoundStatement boundStatement = insertCheckV2PS.bind()
                .setString(i++, agentRollupId)
                .setString(i++, fullTextSha1)
                .setInt(i, getTTL());
            futures.add(session.writeAsync(boundStatement).toCompletableFuture());
        }
        if (!rateLimiter.tryAcquire(fullTextSha1)) {
            return futures;
        }
        try {
            futures.add(storeInternal(fullTextSha1, fullText)
                    .whenComplete((ret, throwable) -> {
                        if (throwable != null) {
                            rateLimiter.release(fullTextSha1);
                        }
                    }).toCompletableFuture());
        } catch (RuntimeException t) {
            rateLimiter.release(fullTextSha1);
            throw t;
        }
        return futures;
    }

    private @Nullable String getFullTextUsingPS(String agentRollupId, String fullTextSha1,
            PreparedStatement readCheckPS) {
        BoundStatement boundStatement = readCheckPS.bind()
            .setString(0, agentRollupId)
            .setString(1, fullTextSha1);
        ResultSet results = session.read(boundStatement);
        if (results.one() == null) {
            return null;
        }
        boundStatement = readPS.bind()
            .setString(0, fullTextSha1);
        results = session.read(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        return row.getString(0);
    }

    @CheckReturnValue
    private CompletableFuture<AsyncResultSet> storeInternal(String fullTextSha1, String fullText) {
        BoundStatement boundStatement = readTtlPS.bind()
            .setString(0, fullTextSha1);
        return session.readAsync(boundStatement).thenCompose(results -> {
            Row row = results.one();
            int ttl = getTTL();
            if (row == null) {
                return insertAndCompleteFuture(fullTextSha1, fullText, ttl);
            }
            int existingTTL = row.getInt(0);
            if (existingTTL != 0 && ttl > existingTTL + DAYS.toSeconds(1)) {
                // only overwrite if bumping TTL at least 1 day
                // also, never overwrite with smaller TTL
                return insertAndCompleteFuture(fullTextSha1, fullText, ttl);
            } else {
                return CompletableFuture.completedFuture(null);
            }
        }).toCompletableFuture();
    }

    private CompletionStage<AsyncResultSet> insertAndCompleteFuture(
            String fullTextSha1, String fullText, int ttl) {
        int i = 0;
        BoundStatement boundStatement = insertPS.bind()
                .setString(i++, fullTextSha1)
                .setString(i++, fullText)
                .setInt(i, ttl);
        return session.writeAsync(boundStatement);
    }

    private int getTTL() {
        CentralStorageConfig storageConfig = configRepository.getCentralStorageConfig();
        int queryRollupExpirationHours =
                Iterables.getLast(storageConfig.queryAndServiceCallRollupExpirationHours());
        int expirationHours =
                Math.max(queryRollupExpirationHours, storageConfig.traceExpirationHours());
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        RollupConfig lastRollupConfig = Iterables.getLast(rollupConfigs);
        // adding largest rollup interval to account for query being retained longer by rollups
        long ttl = MILLISECONDS.toSeconds(lastRollupConfig.intervalMillis())
                // adding 2 days to account for worst case client side rate limiter + server side
                // rate limiter
                + DAYS.toSeconds(2)
                + HOURS.toSeconds(expirationHours);
        return Ints.saturatedCast(ttl);
    }

    @Override
    public void close() throws Exception {
        MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
        platformMBeanServer.unregisterMBean(ObjectName
                .getInstance("org.glowroot.central:type=FullQueryTextRateLimiter"));
    }

    @Value.Immutable
    @Styles.AllParameters
    interface FullQueryTextKey {
        String agentId();
        String fullTextSha1();
    }
}
