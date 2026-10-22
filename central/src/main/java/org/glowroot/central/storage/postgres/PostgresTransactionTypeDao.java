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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.spotify.futures.CompletableFutures;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.TransactionTypeRepository;

/**
 * Postgres port of Cassandra {@code TransactionTypeDao} (local cache, no ClusterManager).
 */
public class PostgresTransactionTypeDao implements TransactionTypeRepository {

    private final DataSource dataSource;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final Executor asyncExecutor;

    private final RateLimiter<TransactionTypeKey> rateLimiter = new RateLimiter<>();
    private final ConcurrentHashMap<String, CompletableFuture<List<String>>> cache =
            new ConcurrentHashMap<>();

    public PostgresTransactionTypeDao(DataSource dataSource, ConfigRepository configRepository,
            Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletionStage<List<String>> read(String agentRollupId) {
        return cache.computeIfAbsent(agentRollupId, id -> {
            CompletableFuture<List<String>> future = load(id);
            future.whenComplete((result, error) -> {
                if (error != null) {
                    cache.remove(id, future);
                }
            });
            return future;
        });
    }

    @CheckReturnValue
    public CompletionStage<?> store(List<String> agentRollups, String transactionType) {
        return configRepository.getCentralStorageConfig().thenCompose(centralStorageConfig -> {
            int ttlSeconds = centralStorageConfig.getMaxRollupTTL();
            Timestamp expiresAt =
                    PostgresExpires.fromTtlSeconds(ttlSeconds, clock.currentTimeMillis(), clock);
            List<CompletionStage<?>> futures = new ArrayList<>();
            for (String agentRollupId : agentRollups) {
                TransactionTypeKey rateLimiterKey =
                        new TransactionTypeKey(agentRollupId, transactionType);
                if (!rateLimiter.tryAcquire(rateLimiterKey)) {
                    continue;
                }
                CompletableFuture<Void> future = new CompletableFuture<>();
                CompletableFuture.runAsync(() -> {
                    try (Connection connection = dataSource.getConnection();
                            PreparedStatement ps = connection.prepareStatement(
                                    "insert into transaction_type (agent_rollup, transaction_type, "
                                            + "expires_at) values (?, ?, ?) "
                                            + "on conflict (agent_rollup, transaction_type) do update set "
                                            + "expires_at = excluded.expires_at")) {
                        ps.setString(1, agentRollupId);
                        ps.setString(2, transactionType);
                        ps.setTimestamp(3, expiresAt);
                        ps.executeUpdate();
                        cache.remove(agentRollupId);
                        future.complete(null);
                    } catch (SQLException e) {
                        rateLimiter.release(rateLimiterKey);
                        future.completeExceptionally(e);
                    }
                }, asyncExecutor);
                futures.add(future);
            }
            return CompletableFutures.allAsList(futures);
        });
    }

    private CompletableFuture<List<String>> load(String agentRollupId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select transaction_type from transaction_type "
                                    + "where agent_rollup = ? and expires_at > ?")) {
                ps.setString(1, agentRollupId);
                ps.setTimestamp(2, new Timestamp(clock.currentTimeMillis()));
                List<String> transactionTypes = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        transactionTypes.add(rs.getString(1));
                    }
                }
                return transactionTypes;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    private static final class TransactionTypeKey {
        private final String agentRollupId;
        private final String transactionType;

        private TransactionTypeKey(String agentRollupId, String transactionType) {
            this.agentRollupId = agentRollupId;
            this.transactionType = transactionType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TransactionTypeKey)) {
                return false;
            }
            TransactionTypeKey that = (TransactionTypeKey) o;
            return agentRollupId.equals(that.agentRollupId)
                    && transactionType.equals(that.transactionType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(agentRollupId, transactionType);
        }
    }
}
