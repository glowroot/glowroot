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
package org.glowroot.central.storage;

import java.nio.ByteBuffer;
import java.util.List;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.glowroot.storage.repo.ImmutableServerRollup;
import org.glowroot.storage.repo.ServerRepository;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.ProcessInfo;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO need to validate cannot have serverIds "A/B/C" and "A/B" since there is logic elsewhere
// (at least in the UI) that "A/B" is only a rollup
public class ServerDao implements ServerRepository {

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement insertProcessInfoPS;

    public ServerDao(Session session) {
        this.session = session;

        session.execute("create table if not exists server (one int, server_rollup varchar,"
                + " leaf boolean, process_info blob, primary key (one, server_rollup))");

        insertPS =
                session.prepare("insert into server (one, server_rollup, leaf) values (1, ?, ?)");

        insertProcessInfoPS = session.prepare("insert into server (one, server_rollup, leaf,"
                + " process_info) values (1, ?, true, ?)");
    }

    @Override
    public List<ServerRollup> readServerRollups() {
        ResultSet results = session.execute("select server_rollup, leaf from server where one = 1");
        List<ServerRollup> rollups = Lists.newArrayList();
        for (Row row : results) {
            String serverRollup = checkNotNull(row.getString(0));
            boolean leaf = row.getBool(1);
            rollups.add(ImmutableServerRollup.of(serverRollup, leaf));
        }
        return rollups;
    }

    @Override
    public void storeProcessInfo(String serverId, ProcessInfo processInfo) {
        BoundStatement boundStatement = insertProcessInfoPS.bind();
        boundStatement.setString(0, serverId);
        boundStatement.setBytes(1, processInfo.toByteString().asReadOnlyByteBuffer());
        session.execute(boundStatement);
    }

    @Override
    public @Nullable ProcessInfo readProcessInfo(String serverId)
            throws InvalidProtocolBufferException {
        ResultSet results = session.execute("select process_info from server where one = 1"
                + " and server_rollup = ?", serverId);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        ByteBuffer bytes = row.getBytes(0);
        if (bytes == null) {
            return null;
        }
        return ProcessInfo.parseFrom(ByteString.copyFrom(bytes));
    }

    void updateLastCaptureTime(String serverRollup, boolean leaf) {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setBool(1, leaf);
        session.execute(boundStatement);
    }
}
