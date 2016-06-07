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
import org.glowroot.storage.repo.AgentRepository;
import org.glowroot.storage.repo.ImmutableAgentRollup;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.SystemInfo;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class AgentDao implements AgentRepository {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("system_info", ColumnType.VARBINARY));

    private final DataSource dataSource;

    AgentDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("agent", columns);
        init(dataSource);
    }

    @Override
    public List<AgentRollup> readAgentRollups() throws Exception {
        return ImmutableList.<AgentRollup>of(ImmutableAgentRollup.builder()
                .name("")
                .leaf(true)
                .build());
    }

    public void store(SystemInfo systemInfo) throws Exception {
        dataSource.update(new SystemInfoBinder(systemInfo));
    }

    @Override
    public @Nullable SystemInfo readSystemInfo(String agentId) throws Exception {
        return dataSource.queryAtMostOne(new SystemInfoRowMapper());
    }

    void reinitAfterDeletingDatabase() throws Exception {
        init(dataSource);
    }

    private static void init(DataSource dataSource) throws SQLException {
        long rowCount = dataSource.queryForLong("select count(*) from agent");
        if (rowCount == 0) {
            dataSource.execute("insert into agent (system_info) values (null)");
        } else {
            checkState(rowCount == 1);
        }
    }

    private static class SystemInfoBinder implements JdbcUpdate {

        private final SystemInfo systemInfo;

        private SystemInfoBinder(SystemInfo systemInfo) {
            this.systemInfo = systemInfo;
        }

        @Override
        public @Untainted String getSql() {
            return "update agent set system_info = ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setBytes(1, systemInfo.toByteArray());
        }
    }

    private static class SystemInfoRowMapper implements JdbcRowQuery<SystemInfo> {

        @Override
        public @Untainted String getSql() {
            return "select system_info from agent where system_info is not null";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) {}

        @Override
        public SystemInfo mapRow(ResultSet resultSet) throws Exception {
            byte[] bytes = resultSet.getBytes(1);
            // query already filters out null system_info
            checkNotNull(bytes);
            return SystemInfo.parseFrom(bytes);
        }
    }
}
