/*
 * Copyright 2015-2019 the original author or authors.
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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.datastax.oss.driver.api.core.cql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.repo.ConfigRepository.OptimisticLockException;

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

    private final Cache<String, Optional<Object>> centralConfigCache;

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

        centralConfigCache = clusterManager.createSelfBoundedCache("centralConfigCache",
                new CentralConfigCacheLoader());
    }

    void addKeyType(String key, Class<?> clazz) {
        keyTypes.put(key, clazz);
    }

    void write(String key, Object config, String priorVersion) throws Exception {
        BoundStatement boundStatement = readPS.bind()
            .setString(0, key);
        ResultSet results = session.read(boundStatement);
        Row row = results.one();
        if (row == null) {
            writeIfNotExists(key, config);
            return;
        }
        String currValue = checkNotNull(row.getString(0));
        Object currConfig = readValue(key, currValue);
        if (!Versions.getJsonVersion(currConfig).equals(priorVersion)) {
            throw new OptimisticLockException();
        }
        String newValue = mapper.writeValueAsString(config);
        int i = 0;
        boundStatement = updatePS.bind()
            .setString(i++, newValue)
            .setString(i++, key)
            .setString(i++, currValue);
        AsyncResultSet asyncresults = session.update(boundStatement);
        row = checkNotNull(asyncresults.one());
        boolean applied = row.getBoolean("[applied]");
        if (applied) {
            centralConfigCache.invalidate(key);
        } else {
            throw new OptimisticLockException();
        }
    }

    void writeWithoutOptimisticLocking(String key, Object config) throws Exception {
        String json = mapper.writeValueAsString(config);
        if (json.equals("{}")) {
            BoundStatement boundStatement = deletePS.bind()
                .setString(0, key);
            session.write(boundStatement);
        } else {
            BoundStatement boundStatement = insertPS.bind()
                .setString(0, key)
                .setString(1, json);
            session.write(boundStatement);
        }
        centralConfigCache.invalidate(key);
    }

    @Nullable
    Object read(String key) {
        return centralConfigCache.get(key).orNull();
    }

    private void writeIfNotExists(String key, Object config) throws Exception {
        String initialValue = mapper.writeValueAsString(config);
        int i = 0;
        BoundStatement boundStatement = insertIfNotExistsPS.bind()
            .setString(i++, key)
            .setString(i++, initialValue);
        AsyncResultSet results = session.update(boundStatement);
        Row row = checkNotNull(results.one());
        boolean applied = row.getBoolean("[applied]");
        if (applied) {
            centralConfigCache.invalidate(key);
        } else {
            throw new OptimisticLockException();
        }
    }

    private Object readValue(String key, String value) throws IOException {
        Class<?> type = checkNotNull(keyTypes.get(key));
        // config is non-null b/c text "null" is never stored
        return checkNotNull(mapper.readValue(value, type));
    }

    private class CentralConfigCacheLoader implements CacheLoader<String, Optional<Object>> {
        @Override
        public Optional<Object> load(String key) {
            BoundStatement boundStatement = readPS.bind()
                .setString(0, key);
            ResultSet results = session.read(boundStatement);
            Row row = results.one();
            if (row == null) {
                return Optional.absent();
            }
            String value = row.getString(0);
            if (value == null) {
                return Optional.absent();
            }
            try {
                return Optional.of(readValue(key, value));
            } catch (IOException e) {
                logger.error("error parsing config node '{}': ", key, e);
                return Optional.absent();
            }
        }
    }
}
