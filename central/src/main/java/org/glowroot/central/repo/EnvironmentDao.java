/*
 * Copyright 2017 the original author or authors.
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

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;

import org.glowroot.central.util.Session;
import org.glowroot.common.repo.EnvironmentRepository;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO environment records never expire for abandoned agent ids
public class EnvironmentDao implements EnvironmentRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    EnvironmentDao(Session session) throws Exception {
        this.session = session;

        session.execute("create table if not exists environment (agent_id varchar,"
                + " environment blob, primary key (agent_id)) " + WITH_LCS);

        insertPS = session.prepare("insert into environment (agent_id, environment) values (?, ?)");
        readPS = session.prepare("select environment from environment where agent_id = ?");
    }

    public void store(String agentId, Environment environment) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(environment.toByteArray()));
        session.execute(boundStatement);
    }

    @Override
    public @Nullable Environment read(String agentId) throws Exception {
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setString(0, agentId);
        Row row = session.execute(boundStatement).one();
        if (row == null) {
            return null;
        }
        return Environment.parseFrom(checkNotNull(row.getBytes(0)));
    }
}
