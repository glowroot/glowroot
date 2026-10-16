/*
 * Copyright 2026 the original author or authors.
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
import java.util.concurrent.CompletionStage;

import org.glowroot.central.storage.postgres.PostgresUserDao;
import org.glowroot.common2.config.UserConfig;
import org.glowroot.common2.repo.CassandraProfile;

final class PostgresUserStore implements UserStore {

    private final PostgresUserDao dao;

    PostgresUserStore(PostgresUserDao dao) {
        this.dao = dao;
    }

    @Override
    public CompletionStage<List<UserConfig>> read() {
        return dao.read();
    }

    @Override
    @SuppressWarnings("nullness")
    public CompletionStage<UserConfig> read(String username) {
        return dao.read(username);
    }

    @Override
    @SuppressWarnings("nullness")
    public CompletionStage<UserConfig> readCaseInsensitive(String username) {
        return dao.readCaseInsensitive(username);
    }

    @Override
    public CompletionStage<Boolean> namedUsersExist() {
        return dao.namedUsersExist();
    }

    @Override
    public CompletionStage<Void> insert(UserConfig userConfig, CassandraProfile profile) {
        return dao.insert(userConfig);
    }

    @Override
    public CompletionStage<?> insertIfNotExists(UserConfig userConfig, CassandraProfile profile) {
        return dao.insertIfNotExists(userConfig);
    }

    @Override
    public CompletionStage<Void> delete(String username, CassandraProfile profile) {
        return dao.delete(username);
    }
}
