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
package org.glowroot.storage.simplerepo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.storage.repo.ImmutableServerRollup;
import org.glowroot.storage.repo.ServerRepository;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.JdbcRowQuery;
import org.glowroot.storage.simplerepo.util.DataSource.JdbcUpdate;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.Schemas.Column;
import org.glowroot.storage.simplerepo.util.Schemas.ColumnType;
import org.glowroot.wire.api.model.JvmInfoOuterClass.JvmInfo;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

class ServerDao implements ServerRepository {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("jvm_info", ColumnType.VARBINARY));

    private final DataSource dataSource;

    ServerDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("server", columns);
        long rowCount = dataSource.queryForLong("select count(*) from server");
        if (rowCount == 0) {
            dataSource.execute("insert into server (jvm_info) values (null)");
        } else {
            checkState(rowCount == 1);
        }
    }

    @Override
    public List<ServerRollup> readServerRollups() throws Exception {
        return ImmutableList.<ServerRollup>of(ImmutableServerRollup.builder()
                .name("")
                .leaf(true)
                .build());
    }

    @Override
    public void storeJvmInfo(String serverId, JvmInfo jvmInfo) throws Exception {
        dataSource.update(new JvmInfoBinder(jvmInfo));
    }

    @Override
    public @Nullable JvmInfo readJvmInfo(String serverId) throws Exception {
        return dataSource.queryAtMostOne(new JvmInfoRowMapper());
    }

    private static class JvmInfoBinder implements JdbcUpdate {

        private final JvmInfo jvmInfo;

        private JvmInfoBinder(JvmInfo jvmInfo) {
            this.jvmInfo = jvmInfo;
        }

        @Override
        public @Untainted String getSql() {
            return "update server set jvm_info = ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setBytes(1, jvmInfo.toByteArray());
        }
    }

    private static class JvmInfoRowMapper implements JdbcRowQuery<JvmInfo> {

        @Override
        public @Untainted String getSql() {
            return "select jvm_info from server where jvm_info is not null";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) {}

        @Override
        public JvmInfo mapRow(ResultSet resultSet) throws Exception {
            byte[] bytes = resultSet.getBytes(1);
            // query already filters out null jvm_info
            checkNotNull(bytes);
            return JvmInfo.parseFrom(bytes);
        }
    }
}
