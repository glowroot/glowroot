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
import org.glowroot.wire.api.model.JvmInfoOuterClass.JvmInfo;

// TODO need to validate cannot have serverIds "A/B/C" and "A/B" since there is logic elsewhere
// (at least in the UI) that "A/B" is only a rollup
public class ServerDao implements ServerRepository {

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement insertJvmInfoPS;

    public ServerDao(Session session) {
        this.session = session;

        session.execute("create table if not exists server (one int, server_rollup varchar,"
                + " leaf boolean, jvm_info blob, primary key (one, server_rollup))");

        insertPS =
                session.prepare("insert into server (one, server_rollup, leaf) values (1, ?, ?)");

        insertJvmInfoPS = session.prepare(
                "insert into server (one, server_rollup, leaf, jvm_info) values (1, ?, true, ?)");
    }

    @Override
    public List<ServerRollup> readServerRollups() {
        ResultSet results = session.execute("select server_rollup, leaf from server where one = 1");
        List<ServerRollup> rollups = Lists.newArrayList();
        for (Row row : results) {
            rollups.add(ImmutableServerRollup.of(row.getString(0), row.getBool(1)));
        }
        return rollups;
    }

    @Override
    public void storeJvmInfo(String serverId, JvmInfo jvmInfo) {
        BoundStatement boundStatement = insertJvmInfoPS.bind();
        boundStatement.setString(0, serverId);
        boundStatement.setBytes(1, jvmInfo.toByteString().asReadOnlyByteBuffer());
        session.execute(boundStatement);
    }

    @Override
    public @Nullable JvmInfo readJvmInfo(String serverId) throws InvalidProtocolBufferException {
        ResultSet results = session.execute("select jvm_info from server where one = 1"
                + " and server_rollup = ?", serverId);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        ByteBuffer bytes = row.getBytes(0);
        if (bytes == null) {
            return null;
        }
        return JvmInfo.parseFrom(ByteString.copyFrom(bytes));
    }

    void updateLastCaptureTime(String serverRollup, boolean leaf) {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setBool(1, leaf);
        session.execute(boundStatement);
    }
}
