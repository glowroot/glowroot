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
package org.glowroot.agent.fat.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.agent.fat.storage.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.fat.storage.util.DataSource.JdbcUpdate;
import org.glowroot.agent.fat.storage.util.ImmutableColumn;
import org.glowroot.agent.fat.storage.util.Schemas.Column;
import org.glowroot.agent.fat.storage.util.Schemas.ColumnType;
import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.ImmutableAgentRollup;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class AgentDao implements AgentRepository {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("environment", ColumnType.VARBINARY));

    private final DataSource dataSource;

    AgentDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        // upgrade from 0.9.1 to 0.9.2
        dataSource.renameColumn("agent", "system_info", "environment");
        dataSource.syncTable("agent", columns);
        init(dataSource);
    }

    @Override
    public List<AgentRollup> readAgentRollups() {
        return ImmutableList.<AgentRollup>of(ImmutableAgentRollup.builder().name("").build());
    }

    @Override
    public boolean isLeaf(String agentRollup) {
        return true;
    }

    public void store(Environment environment) throws Exception {
        dataSource.update(new EnvironmentBinder(environment));
    }

    @Override
    public @Nullable Environment readEnvironment(String agentId) throws Exception {
        return dataSource.queryAtMostOne(new EnvironmentRowMapper());
    }

    void reinitAfterDeletingDatabase() throws Exception {
        init(dataSource);
    }

    private static void init(DataSource dataSource) throws SQLException {
        long rowCount = dataSource.queryForLong("select count(*) from agent");
        if (rowCount == 0) {
            dataSource.execute("insert into agent (environment) values (null)");
        } else {
            checkState(rowCount == 1);
        }
    }

    private static class EnvironmentBinder implements JdbcUpdate {

        private final Environment environment;

        private EnvironmentBinder(Environment environment) {
            this.environment = environment;
        }

        @Override
        public @Untainted String getSql() {
            return "update agent set environment = ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setBytes(1, environment.toByteArray());
        }
    }

    private static class EnvironmentRowMapper implements JdbcRowQuery<Environment> {

        @Override
        public @Untainted String getSql() {
            return "select environment from agent where environment is not null";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) {}

        @Override
        public Environment mapRow(ResultSet resultSet) throws Exception {
            byte[] bytes = resultSet.getBytes(1);
            // query already filters out null environment
            checkNotNull(bytes);
            return Environment.parseFrom(bytes);
        }
    }
}
