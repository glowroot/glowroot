/*
 * Copyright 2016-2018 the original author or authors.
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

import java.util.ArrayList;
import java.util.List;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.repo.ConfigRepository.DuplicateRoleNameException;

import static com.google.common.base.Preconditions.checkNotNull;

class RoleDao {

    private static final String ALL_ROLES_SINGLE_CACHE_KEY = "x";

    private final Session session;

    private final PreparedStatement readPS;
    private final PreparedStatement insertIfNotExistsPS;
    private final PreparedStatement insertPS;
    private final PreparedStatement deletePS;

    private final PreparedStatement readOnePS;

    private final Cache<String, Optional<RoleConfig>> roleConfigCache;
    private final Cache<String, List<RoleConfig>> allRoleConfigsCache;

    RoleDao(Session session, ClusterManager clusterManager) throws Exception {
        this.session = session;

        boolean createAnonymousRole = session.getTable("role") == null;

        session.createTableWithLCS("create table if not exists role (name varchar, permissions"
                + " set<varchar>, primary key (name))");

        readPS = session.prepare("select name, permissions from role");
        insertIfNotExistsPS =
                session.prepare("insert into role (name, permissions) values (?, ?) if not exists");
        insertPS = session.prepare("insert into role (name, permissions) values (?, ?)");
        deletePS = session.prepare("delete from role where name = ?");

        readOnePS = session.prepare("select name, permissions from role where name = ?");

        if (createAnonymousRole) {
            // don't use "if not exists" here since it's not needed and has more chance to fail,
            // leaving the schema in a bad state (with the role table created, but no Administrator
            // role)
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, "Administrator");
            boundStatement.setSet(i++,
                    ImmutableSet.of("agent:*:transaction", "agent:*:error", "agent:*:jvm",
                            "agent:*:syntheticMonitor", "agent:*:incident", "agent:*:config",
                            "admin"));
            session.write(boundStatement);
        }

        roleConfigCache =
                clusterManager.createCache("roleConfigCache", new RoleConfigCacheLoader());
        allRoleConfigsCache =
                clusterManager.createCache("allRoleConfigsCache", new AllRolesCacheLoader());
    }

    List<RoleConfig> read() throws Exception {
        return allRoleConfigsCache.get(ALL_ROLES_SINGLE_CACHE_KEY);
    }

    @Nullable
    RoleConfig read(String name) throws Exception {
        return roleConfigCache.get(name).orNull();
    }

    void delete(String name) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, name);
        session.write(boundStatement);
        roleConfigCache.invalidate(name);
        allRoleConfigsCache.invalidate(ALL_ROLES_SINGLE_CACHE_KEY);
    }

    void insert(RoleConfig roleConfig) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        bindInsert(boundStatement, roleConfig);
        session.write(boundStatement);
        roleConfigCache.invalidate(roleConfig.name());
        allRoleConfigsCache.invalidate(ALL_ROLES_SINGLE_CACHE_KEY);

    }

    void insertIfNotExists(RoleConfig roleConfig) throws Exception {
        BoundStatement boundStatement = insertIfNotExistsPS.bind();
        bindInsert(boundStatement, roleConfig);
        ResultSet results = session.update(boundStatement);
        Row row = checkNotNull(results.one());
        boolean applied = row.getBool("[applied]");
        if (applied) {
            roleConfigCache.invalidate(roleConfig.name());
            allRoleConfigsCache.invalidate(ALL_ROLES_SINGLE_CACHE_KEY);
        } else {
            throw new DuplicateRoleNameException();
        }
    }

    private static void bindInsert(BoundStatement boundStatement, RoleConfig userConfig) {
        int i = 0;
        boundStatement.setString(i++, userConfig.name());
        boundStatement.setSet(i++, userConfig.permissions());
    }

    private static RoleConfig buildRole(Row row) {
        int i = 0;
        return ImmutableRoleConfig.builder()
                .central(true)
                .name(checkNotNull(row.getString(i++)))
                .permissions(row.getSet(i++, String.class))
                .build();
    }

    private class RoleConfigCacheLoader implements CacheLoader<String, Optional<RoleConfig>> {
        @Override
        public Optional<RoleConfig> load(String name) throws Exception {
            BoundStatement boundStatement = readOnePS.bind();
            boundStatement.setString(0, name);
            ResultSet results = session.read(boundStatement);
            if (results.isExhausted()) {
                return Optional.absent();
            }
            Row row = results.one();
            if (!results.isExhausted()) {
                throw new IllegalStateException("Multiple role records for name: " + name);
            }
            return Optional.of(buildRole(row));
        }
    }

    private class AllRolesCacheLoader implements CacheLoader<String, List<RoleConfig>> {
        @Override
        public List<RoleConfig> load(String dummy) throws Exception {
            ResultSet results = session.read(readPS.bind());
            List<RoleConfig> role = new ArrayList<>();
            for (Row row : results) {
                role.add(buildRole(row));
            }
            return role;
        }
    }
}
