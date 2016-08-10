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
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.storage.config.ImmutableRoleConfig;
import org.glowroot.storage.config.PermissionParser;
import org.glowroot.storage.config.RoleConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public class RoleDao {

    private static final Logger logger = LoggerFactory.getLogger(RoleDao.class);

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final String ALL_ROLES_SINGLE_CACHE_KEY = "x";

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

    private final LoadingCache<String, List<RoleConfig>> allRolesCache = CacheBuilder.newBuilder()
            .build(new CacheLoader<String, List<RoleConfig>>() {
                @Override
                public List<RoleConfig> load(String dummy) throws Exception {
                    return readInternal();
                }
            });

    public RoleDao(Session session, KeyspaceMetadata keyspaceMetadata) {
        this.session = session;

        TableMetadata roleTable = keyspaceMetadata.getTable("role");
        boolean createAnonymousRole = roleTable == null;

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
            boundStatement.setSet(i++, ImmutableSet.of("agent:*:transaction", "agent:*:error",
                    "agent:*:jvm", "agent:*:config", "admin"));
            session.execute(boundStatement);
        }

        upgradeIfNeeded(insertPS, session);
    }

    List<RoleConfig> read() {
        return allRolesCache.getUnchecked(ALL_ROLES_SINGLE_CACHE_KEY);
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
        allRolesCache.invalidate(ALL_ROLES_SINGLE_CACHE_KEY);
    }

    void insert(RoleConfig userConfig) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, userConfig.name());
        boundStatement.setSet(i++, userConfig.permissions());
        session.execute(boundStatement);
        cache.invalidate(userConfig.name());
        allRolesCache.invalidate(ALL_ROLES_SINGLE_CACHE_KEY);
    }

    private List<RoleConfig> readInternal() {
        ResultSet results = session.execute(readPS.bind());
        List<RoleConfig> users = Lists.newArrayList();
        for (Row row : results) {
            users.add(buildRole(row));
        }
        return users;
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

    private static void upgradeIfNeeded(PreparedStatement insertPS, Session session) {
        ResultSet results = session.execute("select name, permissions from role");
        for (Row row : results) {
            String name = row.getString(0);
            Set<String> permissions = row.getSet(1, String.class);
            Set<String> upgradedPermissions = upgradePermissions(permissions);
            if (upgradedPermissions == null) {
                continue;
            }
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, name);
            boundStatement.setSet(1, upgradedPermissions, String.class);
            session.execute(boundStatement);
        }
    }

    @VisibleForTesting
    static @Nullable Set<String> upgradePermissions(Set<String> permissions) {
        Set<String> updatedPermissions = Sets.newHashSet();
        ListMultimap<String, String> agentPermissions = ArrayListMultimap.create();
        boolean needsUpgrade = false;
        for (String permission : permissions) {
            if (permission.startsWith("agent:")) {
                PermissionParser parser = new PermissionParser(permission);
                parser.parse();
                String perm = parser.getPermission();
                agentPermissions.put(PermissionParser.quoteIfNecessaryAndJoin(parser.getAgentIds()),
                        perm);
                if (perm.equals("agent:view")) {
                    needsUpgrade = true;
                }
            } else if (permission.equals("admin") || permission.startsWith("admin:")) {
                updatedPermissions.add(permission);
            } else {
                logger.error("unexpected permission: {}", permission);
            }
        }
        if (!needsUpgrade) {
            return null;
        }
        for (Entry<String, List<String>> entry : Multimaps.asMap(agentPermissions).entrySet()) {
            List<String> perms = entry.getValue();
            PermissionParser.upgradeAgentPermissions(perms);
            for (String perm : perms) {
                updatedPermissions
                        .add("agent:" + entry.getKey() + ":" + perm.substring("agent:".length()));
            }
        }
        if (updatedPermissions.contains("admin:view")
                && updatedPermissions.contains("admin:edit")) {
            updatedPermissions.remove("admin:view");
            updatedPermissions.remove("admin:edit");
            updatedPermissions.add("admin");
        }
        return updatedPermissions;
    }

    private static ImmutableRoleConfig buildRole(Row row) {
        int i = 0;
        return ImmutableRoleConfig.builder()
                .fat(false)
                .name(checkNotNull(row.getString(i++)))
                .permissions(row.getSet(i++, String.class))
                .build();
    }
}
