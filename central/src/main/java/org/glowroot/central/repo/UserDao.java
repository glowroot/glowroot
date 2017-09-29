/*
 * Copyright 2016-2017 the original author or authors.
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

import java.util.List;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.config.ImmutableUserConfig;
import org.glowroot.common.config.UserConfig;
import org.glowroot.common.repo.ConfigRepository.DuplicateUsernameException;

import static com.google.common.base.Preconditions.checkNotNull;

class UserDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final String ALL_USERS_SINGLE_CACHE_KEY = "x";

    private final Session session;

    private final PreparedStatement readPS;
    private final PreparedStatement insertIfNotExistsPS;
    private final PreparedStatement insertPS;
    private final PreparedStatement deletePS;

    private final Cache<String, List<UserConfig>> allUserConfigsCache;

    UserDao(Session session, KeyspaceMetadata keyspaceMetadata,
            ClusterManager clusterManager) throws Exception {
        this.session = session;

        boolean createAnonymousUser = keyspaceMetadata.getTable("user") == null;

        session.execute("create table if not exists user (username varchar, ldap boolean,"
                + " password_hash varchar, roles set<varchar>, primary key (username)) "
                + WITH_LCS);

        readPS = session.prepare("select username, ldap, password_hash, roles from user");
        insertIfNotExistsPS = session.prepare("insert into user (username, ldap, password_hash,"
                + " roles) values (?, ?, ?, ?) if not exists");
        insertPS = session.prepare(
                "insert into user (username, ldap, password_hash, roles) values (?, ?, ?, ?)");
        deletePS = session.prepare("delete from user where username = ?");

        if (createAnonymousUser) {
            BoundStatement boundStatement = insertIfNotExistsPS.bind();
            int i = 0;
            boundStatement.setString(i++, "anonymous");
            boundStatement.setBool(i++, false);
            boundStatement.setString(i++, "");
            boundStatement.setSet(i++, ImmutableSet.of("Administrator"));
            session.execute(boundStatement);
        }

        allUserConfigsCache =
                clusterManager.createCache("allUserConfigsCache", new AllUsersCacheLoader());
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
        bindInsert(boundStatement, userConfig);
        session.execute(boundStatement);
        allUserConfigsCache.invalidate(ALL_USERS_SINGLE_CACHE_KEY);
    }

    void insertIfNotExists(UserConfig userConfig) throws Exception {
        BoundStatement boundStatement = insertIfNotExistsPS.bind();
        bindInsert(boundStatement, userConfig);
        ResultSet results = session.execute(boundStatement);
        Row row = checkNotNull(results.one());
        boolean applied = row.getBool("[applied]");
        if (applied) {
            allUserConfigsCache.invalidate(ALL_USERS_SINGLE_CACHE_KEY);
        } else {
            throw new DuplicateUsernameException();
        }
    }

    void delete(String username) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, username);
        session.execute(boundStatement);
        allUserConfigsCache.invalidate(ALL_USERS_SINGLE_CACHE_KEY);
    }

    private static void bindInsert(BoundStatement boundStatement, UserConfig userConfig) {
        int i = 0;
        boundStatement.setString(i++, userConfig.username());
        boundStatement.setBool(i++, userConfig.ldap());
        boundStatement.setString(i++, userConfig.passwordHash());
        boundStatement.setSet(i++, userConfig.roles());
    }

    private class AllUsersCacheLoader implements CacheLoader<String, List<UserConfig>> {
        @Override
        public List<UserConfig> load(String dummy) throws Exception {
            ResultSet results = session.execute(readPS.bind());
            List<UserConfig> users = Lists.newArrayList();
            for (Row row : results) {
                users.add(buildUser(row));
            }
            return users;
        }

        private ImmutableUserConfig buildUser(Row row) {
            int i = 0;
            return ImmutableUserConfig.builder()
                    .username(checkNotNull(row.getString(i++)))
                    .ldap(row.getBool(i++))
                    .passwordHash(checkNotNull(row.getString(i++)))
                    .roles(row.getSet(i++, String.class))
                    .build();
        }
    }
}
