/*
 * Copyright 2015 the original author or authors.
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

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.storage.simplerepo.util.Schema.Column;
import org.glowroot.storage.simplerepo.util.Schema.ColumnType;

public class ConfigDao {

    private static final Logger logger = LoggerFactory.getLogger(ConfigDao.class);

    private static final ImmutableList<Column> configColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("key", ColumnType.VARCHAR),
            ImmutableColumn.of("value", ColumnType.VARCHAR)); // json

    private final DataSource dataSource;

    public ConfigDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        Schema schema = dataSource.getSchema();
        schema.syncTable("config", configColumns);
    }

    void write(String key, Object config, ObjectMapper mapper) throws Exception {
        // TODO add unique index on key to prevent race condition in central cluster adding two
        // config records for same key
        if (dataSource.queryForExists("select 1 from config where key = ?", key)) {
            dataSource.update("update config set value = ? where key = ?",
                    mapper.writeValueAsString(config), key);
        } else {
            dataSource.update("insert into config (value, key) values (?, ?)",
                    mapper.writeValueAsString(config), key);
        }
    }

    @Nullable
    <T> T read(String key, Class<T> clazz, ObjectMapper mapper) throws Exception {
        List<String> values =
                dataSource.queryForStringList("select value from config where key = ?", key);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            logger.warn("there is more than one record in config table for key: {}", key);
        }
        try {
            return mapper.readValue(values.get(0), clazz);
        } catch (IOException e) {
            logger.error("error parsing config node '{}': ", key, e);
            return null;
        }
    }

    <T extends /*@NonNull*/Object> /*@Nullable*/T read(String key, TypeReference<T> typeReference,
            ObjectMapper mapper) throws Exception {
        List<String> values =
                dataSource.queryForStringList("select value from config where key = ?", key);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            logger.warn("there is more than one record in config table for key: {}", key);
        }
        try {
            return mapper.readValue(values.get(0), typeReference);
        } catch (IOException e) {
            logger.error("error parsing config node '{}': ", key, e);
            return null;
        }
    }
}
