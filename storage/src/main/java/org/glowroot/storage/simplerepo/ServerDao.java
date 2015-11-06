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
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import org.glowroot.common.util.Clock;
import org.glowroot.storage.repo.ImmutableServerRollup;
import org.glowroot.storage.repo.ServerRepository;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.PreparedStatementBinder;
import org.glowroot.storage.simplerepo.util.DataSource.ResultSetExtractor;
import org.glowroot.storage.simplerepo.util.DataSource.RowMapper;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.storage.simplerepo.util.Schema.Column;
import org.glowroot.storage.simplerepo.util.Schema.ColumnType;
import org.glowroot.storage.simplerepo.util.Schema.Index;
import org.glowroot.wire.api.model.JvmInfoOuterClass.JvmInfo;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

// TODO need to validate cannot have serverIds "A/B/C" and "A/B" since there is logic elsewhere
// (at least in the UI) that "A/B" is only a rollup
class ServerDao implements ServerRepository {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("server_id", ColumnType.VARCHAR),
            ImmutableColumn.of("jvm_info", ColumnType.VARBINARY),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    // important for this to be unique index to prevent race condition in clustered central
    private static final ImmutableList<Index> indexes = ImmutableList.<Index>of(
            ImmutableIndex.builder()
                    .name("server_idx")
                    .addColumns("server_id")
                    .unique(true)
                    .build());

    private final DataSource dataSource;
    private final Clock clock;

    ServerDao(DataSource dataSource, Clock clock) throws Exception {
        this.dataSource = dataSource;
        this.clock = clock;
        Schema schema = dataSource.getSchema();
        schema.syncTable("server", columns);
        schema.syncIndexes("server", indexes);
    }

    @Override
    public List<ServerRollup> readServerRollups() throws Exception {
        Set<String> serverIds =
                dataSource.query("select server_id from server", new ServerIdsExtractor());
        if (serverIds == null) {
            // data source is closing
            return ImmutableList.of();
        }
        Set<String> serverRollups = Sets.newHashSet();
        for (String serverId : serverIds) {
            serverRollups.addAll(getServerRollups(serverId));
        }
        List<ServerRollup> rollups = Lists.newArrayList();
        for (String serverRollup : Ordering.natural().immutableSortedCopy(serverRollups)) {
            boolean leaf = serverIds.contains(serverRollup);
            rollups.add(ImmutableServerRollup.of(serverRollup, leaf));
        }
        return rollups;
    }

    @Override
    public void storeJvmInfo(String serverId, JvmInfo jvmInfo) throws Exception {
        // ensure row is created
        updateLastCaptureTime(serverId, clock.currentTimeMillis());
        dataSource.update("update server set jvm_info = ? where server_id = ?",
                new JvmInfoBinder(serverId, jvmInfo));
    }

    @Override
    public @Nullable JvmInfo readJvmInfo(String serverId) throws Exception {
        List<JvmInfo> jvmInfos = dataSource.query(
                "select jvm_info from server where server_id = ? and jvm_info is not null",
                new JvmInfoRowMapper(), serverId);
        if (jvmInfos.isEmpty()) {
            return null;
        }
        checkState(jvmInfos.size() == 1);
        return jvmInfos.get(0);
    }

    void updateLastCaptureTime(String serverId, long captureTime) throws Exception {
        int updateCount =
                dataSource.update("update server set last_capture_time = ? where server_id = ?",
                        captureTime, serverId);
        if (updateCount == 1) {
            return;
        }
        try {
            dataSource.update("insert into server (server_id, last_capture_time) values (?, ?)",
                    serverId, captureTime);
        } catch (SQLException e) {
            if (dataSource.queryForExists("select 1 from server where server_id = ?", serverId)) {
                // unique constraint violation above, race condition in central cluster, ok
                return;
            }
            throw e;
        }
    }

    void deleteAll(String serverId) throws Exception {
        dataSource.update("delete from server where server_id = ?", serverId);
    }

    void deleteBefore(String serverId, long captureTime) throws Exception {
        dataSource.update("delete from server where server_id = ? and last_capture_time < ?",
                serverId, captureTime);
    }

    static List<String> getServerRollups(String serverId) {
        List<String> serverRollups = Lists.newArrayList();
        int lastFoundIndex = -1;
        int nextFoundIndex;
        while ((nextFoundIndex = serverId.indexOf('/', lastFoundIndex)) != -1) {
            serverRollups.add(serverId.substring(0, nextFoundIndex));
            lastFoundIndex = nextFoundIndex + 1;
        }
        serverRollups.add(serverId);
        return serverRollups;
    }

    private static class ServerIdsExtractor implements ResultSetExtractor<Set<String>> {
        @Override
        public Set<String> extractData(ResultSet resultSet) throws Exception {
            Set<String> serverIds = Sets.newHashSet();
            while (resultSet.next()) {
                serverIds.add(checkNotNull(resultSet.getString(1)));
            }
            return serverIds;
        }
    }

    private static class JvmInfoBinder implements PreparedStatementBinder {

        private final String serverId;
        private final JvmInfo jvmInfo;

        private JvmInfoBinder(String serverId, JvmInfo jvmInfo) {
            this.serverId = serverId;
            this.jvmInfo = jvmInfo;
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setBytes(1, jvmInfo.toByteArray());
            preparedStatement.setString(2, serverId);
        }
    }

    private static class JvmInfoRowMapper implements RowMapper<JvmInfo> {
        @Override
        public JvmInfo mapRow(ResultSet resultSet) throws Exception {
            byte[] bytes = resultSet.getBytes(1);
            // query already filters out null jvm_info
            checkNotNull(bytes);
            return JvmInfo.parseFrom(bytes);
        }
    }
}
