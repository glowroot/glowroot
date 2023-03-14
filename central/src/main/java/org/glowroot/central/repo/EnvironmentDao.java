/*
 * Copyright 2017-2018 the original author or authors.
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

import java.nio.ByteBuffer;

import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.central.util.Session;
import org.glowroot.common2.repo.EnvironmentRepository;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage.Environment;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO environment records never expire for abandoned agent ids
public class EnvironmentDao implements EnvironmentRepository {

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    EnvironmentDao(Session session) throws Exception {
        this.session = session;

        session.createTableWithLCS("create table if not exists environment (agent_id varchar,"
                + " environment blob, primary key (agent_id))");

        insertPS = session.prepare("insert into environment (agent_id, environment) values (?, ?)");
        readPS = session.prepare("select environment from environment where agent_id = ?");
    }

    public void store(String agentId, Environment environment) throws Exception {
        int i = 0;
        BoundStatement boundStatement = insertPS.bind()
            .setString(i++, agentId)
            .setByteBuffer(i++, ByteBuffer.wrap(environment.toByteArray()));
        session.write(boundStatement);
    }

    @Override
    public @Nullable Environment read(String agentId) throws Exception {
        BoundStatement boundStatement = readPS.bind()
            .setString(0, agentId);
        Row row = session.read(boundStatement).one();
        if (row == null) {
            return null;
        }
        return Environment.parseFrom(checkNotNull(row.getByteBuffer(0)));
    }
}
