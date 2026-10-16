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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.glowroot.central.storage.postgres.PostgresCentralConfigDao;

final class PostgresCentralConfigStore implements CentralConfigStore {

    private final PostgresCentralConfigDao dao;

    PostgresCentralConfigStore(PostgresCentralConfigDao dao) {
        this.dao = dao;
    }

    @Override
    public void addKeyType(String key, Class<?> clazz) {
        dao.addKeyType(key, clazz);
    }

    @Override
    public CompletionStage<?> write(String key, Object config, String priorVersion) {
        return dao.write(key, config, priorVersion);
    }

    @Override
    public CompletionStage<?> writeWithoutOptimisticLocking(String key, Object config) {
        return dao.writeWithoutOptimisticLocking(key, config);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Object> read(String key) {
        return (CompletableFuture<Object>) (CompletableFuture<?>) dao.read(key);
    }
}
