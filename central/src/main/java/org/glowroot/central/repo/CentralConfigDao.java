/*
 * Copyright 2015-2023 the original author or authors.
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

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.util.AsyncCache;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Preconditions.checkNotNull;

class CentralConfigDao {

    private static final Logger logger = LoggerFactory.getLogger(CentralConfigDao.class);

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final Session session;

    private final PreparedStatement insertIfNotExistsPS;
    private final PreparedStatement updatePS;
    private final PreparedStatement insertPS;
    private final PreparedStatement deletePS;
    private final PreparedStatement readPS;

    private final AsyncCache<String, Optional<Object>> centralConfigCache;

    private final Map<String, Class<?>> keyTypes = new ConcurrentHashMap<>();

    CentralConfigDao(Session session, ClusterManager clusterManager) throws Exception {
        this.session = session;

        session.createTableWithLCS("create table if not exists central_config (key varchar, value"
                + " varchar, primary key (key))");

        insertIfNotExistsPS = session
                .prepare("insert into central_config (key, value) values (?, ?) if not exists");
        updatePS =
                session.prepare("update central_config set value = ? where key = ? if value = ?");
        insertPS = session.prepare("insert into central_config (key, value) values (?, ?)");
        deletePS = session.prepare("delete from central_config where key = ?");
        readPS = session.prepare("select value from central_config where key = ?");

        centralConfigCache = clusterManager.createSelfBoundedAsyncCache("centralConfigCache",
                new CentralConfigCacheLoader());
    }

    void addKeyType(String key, Class<?> clazz) {
        keyTypes.put(key, clazz);
    }

    CompletionStage<?> write(String key, Object config, String priorVersion) {
        BoundStatement bdstmt = readPS.bind()
                .setString(0, key);
        return session.readAsync(bdstmt, CassandraProfile.web).thenCompose(results -> {
            Row rw = results.one();
            if (rw == null) {
                return writeIfNotExists(key, config);
            }
            String currValue = checkNotNull(rw.getString(0));

            try {
                Object currConfig = readValue(key, currValue);
                if (!Versions.getJsonVersion(currConfig).equals(priorVersion)) {
                    return CompletableFuture.failedFuture(new OptimisticLockException());
                }
                String newValue = mapper.writeValueAsString(config);
                int i = 0;
                BoundStatement boundStatement = updatePS.bind()
                        .setString(i++, newValue)
                        .setString(i++, key)
                        .setString(i++, currValue);
                // consistency level must be at least LOCAL_SERIAL
                if (boundStatement.getSerialConsistencyLevel() != ConsistencyLevel.SERIAL) {
                    boundStatement = boundStatement.setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL);
                }
                return session.updateAsync(boundStatement, CassandraProfile.web).thenCompose(asyncresults -> {
                    Row row = checkNotNull(asyncresults.one());
                    boolean applied = row.getBoolean("[applied]");
                    if (applied) {
                        centralConfigCache.invalidate(key);
                    } else {
                        return CompletableFuture.failedFuture(new OptimisticLockException());
                    }
                    return CompletableFuture.completedFuture(null);
                });
            } catch (JsonProcessingException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    CompletionStage<?> writeWithoutOptimisticLocking(String key, Object config) {
        String json = null;
        try {
            json = mapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletionStage<AsyncResultSet> ret;
        if (json.equals("{}")) {
            BoundStatement boundStatement = deletePS.bind()
                    .setString(0, key);
            ret = session.writeAsync(boundStatement, CassandraProfile.web);
        } else {
            BoundStatement boundStatement = insertPS.bind()
                    .setString(0, key)
                    .setString(1, json);
            ret = session.writeAsync(boundStatement, CassandraProfile.web);
        }
        return ret.thenRun(() -> centralConfigCache.invalidate(key));
    }

    CompletableFuture<Object> read(String key) {
        return centralConfigCache.get(key).thenApply(opt -> opt.orElse(null));
    }

    private CompletionStage<?> writeIfNotExists(String key, Object config) {
        String initialValue = null;
        try {
            initialValue = mapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
        int i = 0;
        BoundStatement boundStatement = insertIfNotExistsPS.bind()
                .setString(i++, key)
                .setString(i++, initialValue);
        // consistency level must be at least LOCAL_SERIAL
        if (boundStatement.getSerialConsistencyLevel() != ConsistencyLevel.SERIAL) {
            boundStatement = boundStatement.setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL);
        }
        return session.updateAsync(boundStatement, CassandraProfile.web).thenCompose(results -> {
            Row row = checkNotNull(results.one());
            boolean applied = row.getBoolean("[applied]");
            if (applied) {
                centralConfigCache.invalidate(key);
            } else {
                return CompletableFuture.failedFuture(new OptimisticLockException());
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private Object readValue(String key, String value) throws JsonProcessingException {
        Class<?> type = checkNotNull(keyTypes.get(key));
        // config is non-null b/c text "null" is never stored
        return checkNotNull(mapper.readValue(value, type));
    }

    private class CentralConfigCacheLoader implements AsyncCache.AsyncCacheLoader<String, Optional<Object>> {
        @Override
        public CompletableFuture<Optional<Object>> load(String key) {
            BoundStatement boundStatement = readPS.bind()
                    .setString(0, key);
            return (CompletableFuture<Optional<Object>>) session.readAsync(boundStatement, CassandraProfile.collector)
                    .thenApply(results -> Optional.ofNullable(results.one())
                            .map(row -> row.getString(0))
                            .flatMap(value -> {
                                try {
                                    return Optional.of(readValue(key, value));
                                } catch (IOException e) {
                                    logger.error("error parsing config node '{}': ", key, e);
                                    return Optional.empty();
                                }
                            }));
        }
    }
}
