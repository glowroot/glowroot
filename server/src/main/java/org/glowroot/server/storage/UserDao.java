/*
 * Copyright 2016 the original author or authors.
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

import java.util.List;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.glowroot.storage.config.ImmutableUserConfig;
import org.glowroot.storage.config.UserConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public class UserDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement readPS;
    private final PreparedStatement insertPS;
    private final PreparedStatement deletePS;

    private final PreparedStatement readOnePS;
    private final PreparedStatement existsPS;

    public UserDao(Session session, KeyspaceMetadata keyspaceMetadata) {
        this.session = session;

        boolean createAnonymousUser = keyspaceMetadata.getTable("user") == null;

        session.execute("create table if not exists user (username varchar, password_hash varchar,"
                + " roles set<varchar>, primary key (username)) " + WITH_LCS);

        readPS = session.prepare("select username, password_hash, roles from user");
        insertPS = session
                .prepare("insert into user (username, password_hash, roles) values (?, ?, ?)");
        deletePS = session.prepare("delete from user where username = ?");

        readOnePS = session
                .prepare("select username, password_hash, roles from user where username = ?");
        existsPS = session.prepare("select username from user where username = ?");

        if (createAnonymousUser) {
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, "anonymous");
            boundStatement.setString(i++, "");
            boundStatement.setSet(i++, ImmutableSet.of("Administrator"));
            session.execute(boundStatement);
        }
    }

    public List<UserConfig> read() {
        ResultSet results = session.execute(readPS.bind());
        List<UserConfig> users = Lists.newArrayList();
        for (Row row : results) {
            users.add(buildUser(row));
        }
        return users;
    }

    public @Nullable UserConfig read(String username) {
        BoundStatement boundStatement = readOnePS.bind();
        boundStatement.setString(0, username);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            return null;
        }
        Row row = results.one();
        if (!results.isExhausted()) {
            throw new IllegalStateException("Multiple user records for username: " + username);
        }
        return buildUser(row);
    }

    public void insert(UserConfig userConfig) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, userConfig.username());
        boundStatement.setString(i++, userConfig.passwordHash());
        boundStatement.setSet(i++, userConfig.roles());
        session.execute(boundStatement);
    }

    public void delete(String username) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, username);
        session.execute(boundStatement);
    }

    public boolean exists(String username) throws Exception {
        BoundStatement boundStatement = existsPS.bind();
        boundStatement.setString(0, username);
        ResultSet results = session.execute(boundStatement);
        return !results.isExhausted();
    }

    private static ImmutableUserConfig buildUser(Row row) {
        int i = 0;
        return ImmutableUserConfig.builder()
                .username(checkNotNull(row.getString(i++)))
                .passwordHash(checkNotNull(row.getString(i++)))
                .roles(row.getSet(i++, String.class))
                .build();
    }
}
