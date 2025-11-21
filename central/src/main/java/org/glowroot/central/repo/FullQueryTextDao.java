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
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import com.spotify.futures.CompletableFutures;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.immutables.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.TimeUnit.*;

class FullQueryTextDao implements AutoCloseable {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;
    private final PreparedStatement insertCheckV2PS;
    private final PreparedStatement readCheckV2PS;
    private final PreparedStatement readCheckV1PS;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;
    private final PreparedStatement readTtlPS;


    FullQueryTextDao(Session session, ConfigRepositoryImpl configRepository)
            throws Exception {
        this.session = session;
        this.configRepository = configRepository;

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

    }

    CompletionStage<String> getFullText(String agentRollupId, String fullTextSha1, CassandraProfile profile) {
        return getFullTextUsingPS(agentRollupId, fullTextSha1, readCheckV2PS, profile).thenCompose(fullText -> {
            if (fullText != null) {
                return CompletableFuture.completedFuture(fullText);
            }
            return getFullTextUsingPS(agentRollupId, fullTextSha1, readCheckV1PS, profile);
        });
    }

    CompletionStage<?> store(List<String> agentRollupIds, String fullTextSha1, String fullText) {
        return getTTL().thenCompose(ttl -> {
            // relying on agent side to rate limit (re-)sending the same full text
            List<CompletionStage<?>> futures = new ArrayList<>();
            for (String agentRollupId : agentRollupIds) {
                int i = 0;
                BoundStatement boundStatement = insertCheckV2PS.bind()
                        .setString(i++, agentRollupId)
                        .setString(i++, fullTextSha1)
                        .setInt(i, ttl);
                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector).toCompletableFuture());
            }
            futures.add(storeInternal(fullTextSha1, fullText));
            return CompletableFutures.allAsList(futures);
        });
    }

    private CompletionStage<String> getFullTextUsingPS(String agentRollupId, String fullTextSha1,
                                                       PreparedStatement readCheckPS, CassandraProfile profile) {
        BoundStatement boundStatement = readCheckPS.bind()
                .setString(0, agentRollupId)
                .setString(1, fullTextSha1);
        return session.readAsync(boundStatement, profile).thenCompose(results -> {
            if (results.one() == null) {
                return CompletableFuture.completedFuture(null);
            }
            BoundStatement boundStatement2 = readPS.bind()
                    .setString(0, fullTextSha1);
            return session.readAsync(boundStatement2, profile).thenApply(results2 -> {
                Row row = results2.one();
                if (row == null) {
                    return null;
                }
                return row.getString(0);
            });
        });
    }

    @CheckReturnValue
    private CompletionStage<?> storeInternal(String fullTextSha1, String fullText) {
        BoundStatement boundStatement = readTtlPS.bind()
                .setString(0, fullTextSha1);
        return getTTL().thenCompose(ttl -> {
            return session.readAsync(boundStatement, CassandraProfile.collector).thenCompose(results -> {
                Row row = results.one();

                if (row == null) {
                    return insertAndCompleteFuture(fullTextSha1, fullText, ttl, CassandraProfile.collector);
                }
                int existingTTL = row.getInt(0);
                if (existingTTL != 0 && ttl > existingTTL + DAYS.toSeconds(1)) {
                    // only overwrite if bumping TTL at least 1 day
                    // also, never overwrite with smaller TTL
                    return insertAndCompleteFuture(fullTextSha1, fullText, ttl, CassandraProfile.collector);
                } else {
                    return CompletableFuture.completedFuture(null);
                }
            });
        });
    }

    private CompletionStage<AsyncResultSet> insertAndCompleteFuture(
            String fullTextSha1, String fullText, int ttl, CassandraProfile profile) {
        int i = 0;
        BoundStatement boundStatement = insertPS.bind()
                .setString(i++, fullTextSha1)
                .setString(i++, fullText)
                .setInt(i, ttl);
        return session.writeAsync(boundStatement, profile);
    }

    private CompletionStage<Integer> getTTL() {
        return configRepository.getCentralStorageConfig().thenApply(storageConfig -> {
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
        });
    }

    @Override
    public void close() throws Exception {
    }

    @Value.Immutable
    @Styles.AllParameters
    interface FullQueryTextKey {
        String agentId();

        String fullTextSha1();
    }
}
