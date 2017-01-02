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
import java.util.Locale;

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

import org.glowroot.common.config.ImmutableUserConfig;
import org.glowroot.common.config.UserConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public class UserDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final String NAMED_USERS_EXIST_SINGLE_CACHE_KEY = "x";

    private final Session session;

    private final PreparedStatement readPS;
    private final PreparedStatement insertPS;
    private final PreparedStatement deletePS;

    private final LoadingCache<String, Optional<UserConfig>> upperCaseCache =
            CacheBuilder.newBuilder()
                    .build(new CacheLoader<String, Optional<UserConfig>>() {
                        @Override
                        public Optional<UserConfig> load(String usernameUpper) throws Exception {
                            for (UserConfig userConfig : read()) {
                                if (userConfig.username().equalsIgnoreCase(usernameUpper)) {
                                    return Optional.of(userConfig);
                                }
                            }
                            return Optional.absent();
                        }
                    });

    private final LoadingCache<String, Boolean> namedUsersExist = CacheBuilder.newBuilder()
            .build(new CacheLoader<String, Boolean>() {
                @Override
                public Boolean load(String dummy) throws Exception {
                    for (UserConfig userConfig : read()) {
                        if (!userConfig.username().equalsIgnoreCase("anonymous")) {
                            return true;
                        }
                    }
                    return false;
                }
            });

    public UserDao(Session session, KeyspaceMetadata keyspaceMetadata) {
        this.session = session;

        boolean createAnonymousUser = keyspaceMetadata.getTable("user") == null;

        session.execute("create table if not exists user (username varchar, ldap boolean,"
                + " password_hash varchar, roles set<varchar>, primary key (username)) "
                + WITH_LCS);

        readPS = session.prepare("select username, ldap, password_hash, roles from user");
        insertPS = session.prepare(
                "insert into user (username, ldap, password_hash, roles) values (?, ?, ?, ?)");
        deletePS = session.prepare("delete from user where username = ?");

        if (createAnonymousUser) {
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, "anonymous");
            boundStatement.setBool(i++, false);
            boundStatement.setString(i++, "");
            boundStatement.setSet(i++, ImmutableSet.of("Administrator"));
            session.execute(boundStatement);
        }
    }

    List<UserConfig> read() {
        ResultSet results = session.execute(readPS.bind());
        List<UserConfig> users = Lists.newArrayList();
        for (Row row : results) {
            users.add(buildUser(row));
        }
        return users;
    }

    @Nullable
    UserConfig read(String username) {
        for (UserConfig userConfig : read()) {
            if (userConfig.username().equals(username)) {
                return userConfig;
            }
        }
        return null;
    }

    @Nullable
    UserConfig readCaseInsensitive(String username) {
        return upperCaseCache.getUnchecked(username.toUpperCase(Locale.ENGLISH)).orNull();
    }

    boolean namedUsersExist() {
        return namedUsersExist.getUnchecked(NAMED_USERS_EXIST_SINGLE_CACHE_KEY);
    }

    void insert(UserConfig userConfig) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, userConfig.username());
        boundStatement.setBool(i++, userConfig.ldap());
        boundStatement.setString(i++, userConfig.passwordHash());
        boundStatement.setSet(i++, userConfig.roles());
        session.execute(boundStatement);
        upperCaseCache.invalidate(userConfig.username().toUpperCase(Locale.ENGLISH));
        namedUsersExist.invalidate(NAMED_USERS_EXIST_SINGLE_CACHE_KEY);
    }

    void delete(String username) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, username);
        session.execute(boundStatement);
        upperCaseCache.invalidate(username.toUpperCase(Locale.ENGLISH));
        namedUsersExist.invalidate(NAMED_USERS_EXIST_SINGLE_CACHE_KEY);
    }

    private static ImmutableUserConfig buildUser(Row row) {
        int i = 0;
        return ImmutableUserConfig.builder()
                .username(checkNotNull(row.getString(i++)))
                .ldap(row.getBool(i++))
                .passwordHash(checkNotNull(row.getString(i++)))
                .roles(row.getSet(i++, String.class))
                .build();
    }
}
