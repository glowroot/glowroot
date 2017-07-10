/*
 * Copyright 2015-2017 the original author or authors.
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

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;

import static com.google.common.base.Preconditions.checkNotNull;

class CentralConfigDao {

    private static final Logger logger = LoggerFactory.getLogger(CentralConfigDao.class);

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final Session session;

    private final PreparedStatement insertIfNotExistsPS;
    private final PreparedStatement updatePS;
    private final PreparedStatement readPS;

    private final Cache<String, Optional<Object>> centralConfigCache;

    private final Map<String, Class<?>> keyTypes = Maps.newConcurrentMap();

    CentralConfigDao(Session session, ClusterManager clusterManager) throws Exception {
        this.session = session;

        session.execute("create table if not exists central_config (key varchar,"
                + " value varchar, primary key (key)) " + WITH_LCS);

        insertIfNotExistsPS = session
                .prepare("insert into central_config (key, value) values (?, ?) if not exists");
        updatePS =
                session.prepare("update central_config set value = ? where key = ? if value = ?");
        readPS = session.prepare("select value from central_config where key = ?");

        centralConfigCache =
                clusterManager.createCache("centralConfigCache", new CentralConfigCacheLoader());
    }

    void addKeyType(String key, Class<?> clazz) {
        keyTypes.put(key, clazz);
    }

    void write(String key, Object config, String priorVersion) throws Exception {
        BoundStatement boundStatement = readPS.bind();
        boundStatement.bind(key);
        ResultSet results = session.execute(boundStatement);
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
        boundStatement = updatePS.bind();
        int i = 0;
        boundStatement.setString(i++, newValue);
        boundStatement.setString(i++, key);
        boundStatement.setString(i++, currValue);
        results = session.execute(boundStatement);
        row = checkNotNull(results.one());
        boolean applied = row.getBool("[applied]");
        if (applied) {
            centralConfigCache.invalidate(key);
        } else {
            throw new OptimisticLockException();
        }
    }

    @Nullable
    Object read(String key) throws Exception {
        return centralConfigCache.get(key).orNull();
    }

    private void writeIfNotExists(String key, Object config) throws Exception {
        String initialValue = mapper.writeValueAsString(config);
        BoundStatement boundStatement = insertIfNotExistsPS.bind();
        int i = 0;
        boundStatement.setString(i++, key);
        boundStatement.setString(i++, initialValue);
        ResultSet results = session.execute(boundStatement);
        Row row = checkNotNull(results.one());
        boolean applied = row.getBool("[applied]");
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
        public Optional<Object> load(String key) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.bind(key);
            ResultSet results = session.execute(boundStatement);
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
