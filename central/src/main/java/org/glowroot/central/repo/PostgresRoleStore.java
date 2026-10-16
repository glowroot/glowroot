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

import org.glowroot.central.storage.postgres.PostgresRoleDao;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.repo.CassandraProfile;

final class PostgresRoleStore implements RoleStore {

    private final PostgresRoleDao dao;

    PostgresRoleStore(PostgresRoleDao dao) {
        this.dao = dao;
    }

    @Override
    public CompletionStage<List<RoleConfig>> read() {
        return dao.read();
    }

    @Override
    @SuppressWarnings("nullness")
    public CompletionStage<RoleConfig> read(String name) {
        return dao.read(name);
    }

    @Override
    public CompletionStage<?> insert(RoleConfig roleConfig, CassandraProfile profile) {
        return dao.insert(roleConfig);
    }

    @Override
    public CompletionStage<?> insertIfNotExists(RoleConfig roleConfig, CassandraProfile profile) {
        return dao.insertIfNotExists(roleConfig);
    }

    @Override
    public CompletionStage<?> delete(String name, CassandraProfile profile) {
        return dao.delete(name);
    }
}
