/*
 * Copyright 2016-2019 the original author or authors.
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

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.*;
import com.google.common.collect.ImmutableSet;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.common2.config.UserConfig;
import org.glowroot.common2.repo.ConfigRepository.DuplicateUsernameException;

import static com.google.common.base.Preconditions.checkNotNull;

class UserDao {

    private static final String ALL_USERS_SINGLE_CACHE_KEY = "x";

    private final Session session;

    private final PreparedStatement readPS;
    private final PreparedStatement insertIfNotExistsPS;
    private final PreparedStatement insertPS;
    private final PreparedStatement deletePS;

    private final Cache<String, List<UserConfig>> allUserConfigsCache;

    UserDao(Session session, ClusterManager clusterManager) throws Exception {
        this.session = session;

        boolean createAnonymousUser = session.getTable("user") == null;

        session.createTableWithLCS("create table if not exists user (username varchar, ldap"
                + " boolean, password_hash varchar, roles set<varchar>, primary key (username))");

        readPS = session.prepare("select username, ldap, password_hash, roles from user");
        insertIfNotExistsPS = session.prepare("insert into user (username, ldap, password_hash,"
                + " roles) values (?, ?, ?, ?) if not exists");
        insertPS = session.prepare(
                "insert into user (username, ldap, password_hash, roles) values (?, ?, ?, ?)");
        deletePS = session.prepare("delete from user where username = ?");

        if (createAnonymousUser) {
            // don't use "if not exists" here since it's not needed and has more chance to fail,
            // leaving the schema in a bad state (with the user table created, but no anonymous
            // user)
            int i = 0;
            BoundStatement boundStatement = insertPS.bind()
                .setString(i++, "anonymous")
                .setBoolean(i++, false)
                .setString(i++, "")
                .setSet(i++, ImmutableSet.of("Administrator"), String.class);
            session.write(boundStatement);
        }

        allUserConfigsCache = clusterManager.createSelfBoundedCache("allUserConfigsCache",
                new AllUsersCacheLoader());
    }

    List<UserConfig> read() throws Exception {
        return allUserConfigsCache.get(ALL_USERS_SINGLE_CACHE_KEY);
    }

    @Nullable
    UserConfig read(String username) throws Exception {
        for (UserConfig userConfig : read()) {
            if (userConfig.username().equals(username)) {
                return userConfig;
            }
        }
        return null;
    }

    @Nullable
    UserConfig readCaseInsensitive(String username) throws Exception {
        for (UserConfig userConfig : read()) {
            if (userConfig.username().equalsIgnoreCase(username)) {
                return userConfig;
            }
        }
        return null;
    }

    boolean namedUsersExist() throws Exception {
        for (UserConfig userConfig : read()) {
            if (!userConfig.username().equalsIgnoreCase("anonymous")) {
                return true;
            }
        }
        return false;
    }

    void insert(UserConfig userConfig) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement = bindInsert(boundStatement, userConfig);
        session.write(boundStatement);
        allUserConfigsCache.invalidate(ALL_USERS_SINGLE_CACHE_KEY);
    }

    void insertIfNotExists(UserConfig userConfig) throws Exception {
        BoundStatement boundStatement = insertIfNotExistsPS.bind();
        boundStatement = bindInsert(boundStatement, userConfig);
        // consistency level must be at least LOCAL_SERIAL
        if (boundStatement.getSerialConsistencyLevel() != ConsistencyLevel.SERIAL) {
            boundStatement = boundStatement.setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL);
        }
        AsyncResultSet results = session.update(boundStatement);
        Row row = checkNotNull(results.one());
        boolean applied = row.getBoolean("[applied]");
        if (applied) {
            allUserConfigsCache.invalidate(ALL_USERS_SINGLE_CACHE_KEY);
        } else {
            throw new DuplicateUsernameException();
        }
    }

    void delete(String username) throws Exception {
        BoundStatement boundStatement = deletePS.bind()
            .setString(0, username);
        session.write(boundStatement);
        allUserConfigsCache.invalidate(ALL_USERS_SINGLE_CACHE_KEY);
    }

    @CheckReturnValue
    private static BoundStatement bindInsert(BoundStatement boundStatement, UserConfig userConfig) {
        int i = 0;
        return boundStatement.setString(i++, userConfig.username())
            .setBoolean(i++, userConfig.ldap())
            .setString(i++, userConfig.passwordHash())
            .setSet(i++, userConfig.roles(), String.class);
    }

    private class AllUsersCacheLoader implements CacheLoader<String, List<UserConfig>> {

        @Override
        public List<UserConfig> load(String dummy) {
            ResultSet results = session.read(readPS.bind());
            List<UserConfig> users = new ArrayList<>();
            for (Row row : results) {
                users.add(buildUser(row));
            }
            return users;
        }

        private UserConfig buildUser(Row row) {
            int i = 0;
            return ImmutableUserConfig.builder()
                    .username(checkNotNull(row.getString(i++)))
                    .ldap(row.getBoolean(i++))
                    .passwordHash(checkNotNull(row.getString(i++)))
                    .roles(row.getSet(i++, String.class))
                    .build();
        }
    }
}
