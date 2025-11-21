/*
 * Copyright 2016-2023 the original author or authors.
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.*;
import com.google.common.collect.ImmutableSet;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.central.util.AsyncCache;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.repo.CassandraProfile;
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

    private final AsyncCache<String, Optional<RoleConfig>> roleConfigCache;
    private final AsyncCache<String, List<RoleConfig>> allRoleConfigsCache;

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
            int i = 0;
            BoundStatement boundStatement = insertPS.bind()
                .setString(i++, "Administrator")
                .setSet(i++,
                    ImmutableSet.of("agent:*:transaction", "agent:*:error", "agent:*:jvm",
                            "agent:*:syntheticMonitor", "agent:*:incident", "agent:*:config",
                            "admin"), String.class);
            session.writeAsync(boundStatement, CassandraProfile.slow).toCompletableFuture().get();
        }

        roleConfigCache = clusterManager.createSelfBoundedAsyncCache("roleConfigCache",
                new RoleConfigCacheLoader());
        allRoleConfigsCache = clusterManager.createSelfBoundedAsyncCache("allRoleConfigsCache",
                new AllRolesCacheLoader());
    }

    CompletionStage<List<RoleConfig>> read() {
        return allRoleConfigsCache.get(ALL_ROLES_SINGLE_CACHE_KEY);
    }

    @CheckReturnValue
    CompletionStage<RoleConfig> read(String name) {
        return roleConfigCache.get(name).thenApply(opt -> opt.orElse(null));
    }

    CompletionStage<?> delete(String name, CassandraProfile profile) {
        BoundStatement boundStatement = deletePS.bind()
            .setString(0, name);
        return session.writeAsync(boundStatement, profile).thenRun(() -> {
            roleConfigCache.invalidate(name);
            allRoleConfigsCache.invalidate(ALL_ROLES_SINGLE_CACHE_KEY);
        });
    }

    CompletionStage<?> insert(RoleConfig roleConfig, CassandraProfile profile) {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement = bindInsert(boundStatement, roleConfig);
        return session.writeAsync(boundStatement, profile).thenRun(() -> {
            roleConfigCache.invalidate(roleConfig.name());
            allRoleConfigsCache.invalidate(ALL_ROLES_SINGLE_CACHE_KEY);
        });
    }

    CompletionStage<?> insertIfNotExists(RoleConfig roleConfig, CassandraProfile profile) {
        BoundStatement boundStatement = insertIfNotExistsPS.bind();
        boundStatement = bindInsert(boundStatement, roleConfig);
        // consistency level must be at least LOCAL_SERIAL
        if (boundStatement.getSerialConsistencyLevel() != ConsistencyLevel.SERIAL) {
            boundStatement = boundStatement.setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL);
        }
        return session.updateAsync(boundStatement, profile).thenCompose(results -> {
            Row row = checkNotNull(results.one());
            boolean applied = row.getBoolean("[applied]");
            if (applied) {
                roleConfigCache.invalidate(roleConfig.name());
                allRoleConfigsCache.invalidate(ALL_ROLES_SINGLE_CACHE_KEY);
                return CompletableFuture.completedFuture(null);
            } else {
                return CompletableFuture.failedFuture(new DuplicateRoleNameException());
            }
        });
    }

    @CheckReturnValue
    private static BoundStatement bindInsert(BoundStatement boundStatement, RoleConfig userConfig) {
        int i = 0;
        return boundStatement.setString(i++, userConfig.name())
            .setSet(i++, userConfig.permissions(), String.class);
    }

    private static RoleConfig buildRole(Row row) {
        int i = 0;
        return ImmutableRoleConfig.builder()
                .central(true)
                .name(checkNotNull(row.getString(i++)))
                .permissions(row.getSet(i, String.class))
                .build();
    }

    private class RoleConfigCacheLoader implements AsyncCache.AsyncCacheLoader<String, Optional<RoleConfig>> {
        @Override
        public CompletableFuture<Optional<RoleConfig>> load(String name) {
            BoundStatement boundStatement = readOnePS.bind()
                .setString(0, name);
            return session.readAsync(boundStatement, CassandraProfile.collector).thenApply(results -> Optional.ofNullable(results.one()).map(row -> {
                if (results.one() != null) {
                    throw new IllegalStateException("Multiple role records for name: " + name);
                }
                return buildRole(row);
            })).toCompletableFuture();
        }
    }

    private class AllRolesCacheLoader implements AsyncCache.AsyncCacheLoader<String, List<RoleConfig>> {
        @Override
        public CompletableFuture<List<RoleConfig>> load(String dummy) {
            List<RoleConfig> role = new ArrayList<>();
            Function<AsyncResultSet, CompletableFuture<List<RoleConfig>>> compute = new Function<>() {
                @Override
                public CompletableFuture<List<RoleConfig>> apply(AsyncResultSet results) {
                    for (Row row : results.currentPage()) {
                        role.add(buildRole(row));
                    }
                    if (results.hasMorePages()) {
                        return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                    }
                    return CompletableFuture.completedFuture(role);
                }
            };
            return session.readAsync(readPS.bind(), CassandraProfile.collector).thenCompose(compute).toCompletableFuture();
        }
    }
}
