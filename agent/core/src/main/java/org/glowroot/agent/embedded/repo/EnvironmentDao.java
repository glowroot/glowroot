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
package org.glowroot.agent.embedded.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.embedded.util.DataSource.JdbcUpdate;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.common.repo.EnvironmentRepository;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class EnvironmentDao implements EnvironmentRepository {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("environment", ColumnType.VARBINARY));

    private final DataSource dataSource;

    EnvironmentDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        // upgrade from 0.9.1 to 0.9.2
        dataSource.renameColumn("agent", "system_info", "environment");
        // upgrade from 0.9.9 to 0.9.10
        dataSource.renameTable("agent", "environment");

        dataSource.syncTable("environment", columns);
        init(dataSource);
    }

    public void store(Environment environment) throws Exception {
        dataSource.update(new EnvironmentBinder(environment));
    }

    @Override
    public @Nullable Environment read(String agentId) throws Exception {
        return dataSource.queryAtMostOne(new EnvironmentRowMapper());
    }

    void reinitAfterDeletingDatabase() throws Exception {
        init(dataSource);
    }

    private static void init(DataSource dataSource) throws SQLException {
        long rowCount = dataSource.queryForLong("select count(*) from environment");
        if (rowCount == 0) {
            dataSource.execute("insert into environment (environment) values (null)");
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
            return "update environment set environment = ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setBytes(1, environment.toByteArray());
        }
    }

    private static class EnvironmentRowMapper implements JdbcRowQuery<Environment> {

        @Override
        public @Untainted String getSql() {
            return "select environment from environment where environment is not null";
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
