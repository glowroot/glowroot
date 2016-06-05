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
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.glowroot.storage.config.ImmutableRoleConfig;
import org.glowroot.storage.config.RoleConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public class RoleDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement readPS;
    private final PreparedStatement insertPS;
    private final PreparedStatement deletePS;

    private final PreparedStatement readOnePS;

    private final LoadingCache<String, Optional<RoleConfig>> cache = CacheBuilder.newBuilder()
            .build(new CacheLoader<String, Optional<RoleConfig>>() {
                @Override
                public Optional<RoleConfig> load(String name) throws Exception {
                    return Optional.fromNullable(readInternal(name));
                }
            });

    public RoleDao(Session session, KeyspaceMetadata keyspaceMetadata) {
        this.session = session;

        boolean createAnonymousRole = keyspaceMetadata.getTable("role") == null;

        session.execute("create table if not exists role (name varchar, permissions set<varchar>,"
                + " primary key (name)) " + WITH_LCS);

        readPS = session.prepare("select name, permissions from role");
        insertPS = session.prepare("insert into role (name, permissions) values (?, ?)");
        deletePS = session.prepare("delete from role where name = ?");

        readOnePS = session.prepare("select name, permissions from role where name = ?");

        if (createAnonymousRole) {
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, "Administrator");
            boundStatement.setSet(i++, ImmutableSet.of("agent:*:view", "agent:*:tool",
                    "agent:*:config:view", "agent:*:config:edit", "admin"));
            session.execute(boundStatement);
        }
    }

    List<RoleConfig> read() {
        ResultSet results = session.execute(readPS.bind());
        List<RoleConfig> users = Lists.newArrayList();
        for (Row row : results) {
            users.add(buildRole(row));
        }
        return users;
    }

    @Nullable
    RoleConfig read(String name) {
        return cache.getUnchecked(name).orNull();
    }

    void delete(String name) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, name);
        session.execute(boundStatement);
        cache.invalidate(name);
    }

    void insert(RoleConfig userConfig) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, userConfig.name());
        boundStatement.setSet(i++, userConfig.permissions());
        session.execute(boundStatement);
        cache.invalidate(userConfig.name());
    }

    private @Nullable RoleConfig readInternal(String name) {
        BoundStatement boundStatement = readOnePS.bind();
        boundStatement.setString(0, name);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            return null;
        }
        Row row = results.one();
        if (!results.isExhausted()) {
            throw new IllegalStateException("Multiple role records for name: " + name);
        }
        return buildRole(row);
    }

    private static ImmutableRoleConfig buildRole(Row row) {
        int i = 0;
        return ImmutableRoleConfig.builder()
                .name(checkNotNull(row.getString(i++)))
                .permissions(row.getSet(i++, String.class))
                .build();
    }
}
