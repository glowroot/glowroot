/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.primitives.Ints;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.TraceAttributeNameRepository;

import static java.util.concurrent.TimeUnit.DAYS;

/**
 * Postgres port of Cassandra {@code TraceAttributeNameDao} (local cache, no ClusterManager).
 */
public class PostgresTraceAttributeNameDao implements TraceAttributeNameRepository {

    private final DataSource dataSource;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final Executor asyncExecutor;

    private final RateLimiter<TraceAttributeNameKey> rateLimiter = new RateLimiter<>();
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, List<String>>>> cache =
            new ConcurrentHashMap<>();

    public PostgresTraceAttributeNameDao(DataSource dataSource, ConfigRepository configRepository,
            Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public Map<String, List<String>> read(String agentRollupId) throws Exception {
        return cache.computeIfAbsent(agentRollupId, this::load).get();
    }

    public CompletionStage<?> store(String agentRollupId, String transactionType,
            String traceAttributeName) {
        TraceAttributeNameKey rateLimiterKey =
                new TraceAttributeNameKey(agentRollupId, transactionType, traceAttributeName);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return CompletableFuture.completedFuture(null);
        }
        return getTraceTTL().thenCompose(ttl -> {
            Timestamp expiresAt =
                    PostgresExpires.fromTtlSeconds(ttl, clock.currentTimeMillis(), clock);
            CompletableFuture<Void> future = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try (Connection connection = dataSource.getConnection();
                        PreparedStatement ps = connection.prepareStatement(
                                "insert into trace_attribute_name (agent_rollup, transaction_type, "
                                        + "trace_attribute_name, expires_at) values (?, ?, ?, ?) "
                                        + "on conflict (agent_rollup, transaction_type, "
                                        + "trace_attribute_name) do update set "
                                        + "expires_at = excluded.expires_at")) {
                    ps.setString(1, agentRollupId);
                    ps.setString(2, transactionType);
                    ps.setString(3, traceAttributeName);
                    ps.setTimestamp(4, expiresAt);
                    ps.executeUpdate();
                    cache.remove(agentRollupId);
                    future.complete(null);
                } catch (SQLException e) {
                    rateLimiter.release(rateLimiterKey);
                    future.completeExceptionally(e);
                }
            }, asyncExecutor);
            return future;
        });
    }

    private CompletionStage<Integer> getTraceTTL() {
        return configRepository.getCentralStorageConfig().thenApply(centralStorageConfig -> {
            int ttl = centralStorageConfig.getTraceTTL();
            if (ttl == 0) {
                return 0;
            }
            return Ints.saturatedCast(ttl + DAYS.toSeconds(1));
        });
    }

    private CompletableFuture<Map<String, List<String>>> load(String agentRollupId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select transaction_type, trace_attribute_name from "
                                    + "trace_attribute_name where agent_rollup = ? "
                                    + "and expires_at > ?")) {
                ps.setString(1, agentRollupId);
                ps.setTimestamp(2, new Timestamp(clock.currentTimeMillis()));
                ListMultimap<String, String> traceAttributeNames = ArrayListMultimap.create();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        traceAttributeNames.put(rs.getString(1), rs.getString(2));
                    }
                }
                return Multimaps.asMap(traceAttributeNames);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    private static final class TraceAttributeNameKey {
        private final String agentRollupId;
        private final String transactionType;
        private final String traceAttributeName;

        private TraceAttributeNameKey(String agentRollupId, String transactionType,
                String traceAttributeName) {
            this.agentRollupId = agentRollupId;
            this.transactionType = transactionType;
            this.traceAttributeName = traceAttributeName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TraceAttributeNameKey)) {
                return false;
            }
            TraceAttributeNameKey that = (TraceAttributeNameKey) o;
            return agentRollupId.equals(that.agentRollupId)
                    && transactionType.equals(that.transactionType)
                    && traceAttributeName.equals(that.traceAttributeName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(agentRollupId, transactionType, traceAttributeName);
        }
    }
}
