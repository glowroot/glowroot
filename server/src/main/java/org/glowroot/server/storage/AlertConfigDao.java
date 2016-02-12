/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.server.storage;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.config.AlertConfig;
import org.glowroot.storage.config.ImmutableAlertConfig;

public class AlertConfigDao {

    private static final Logger logger = LoggerFactory.getLogger(AlertConfigDao.class);

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    public AlertConfigDao(Session session) {
        this.session = session;

        session.execute("create table if not exists alert_config (agent_id varchar,"
                + " value varchar, primary key (agent_id))");

        insertPS = session.prepare("insert into alert_config (agent_id, value) values (?, ?)");

        readPS = session.prepare("select value from alert_config where agent_id = ?");
    }

    void write(String key, List<AlertConfig> alerts) throws JsonProcessingException {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setString(0, key);
        boundStatement.setString(1, mapper.writeValueAsString(alerts));
        session.execute(boundStatement);
    }

    @Nullable
    List<AlertConfig> readAlerts(String agentId) {
        BoundStatement boundStatement = readPS.bind();
        boundStatement.bind(agentId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        String value = row.getString(0);
        if (value == null) {
            return null;
        }
        try {
            return mapper.readValue(value, new TypeReference<List<ImmutableAlertConfig>>() {});
        } catch (IOException e) {
            logger.error("error parsing config node '{}': ", agentId, e);
            return null;
        }
    }
}
