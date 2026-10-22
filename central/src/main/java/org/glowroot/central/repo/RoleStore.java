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

import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.repo.CassandraProfile;

interface RoleStore {

    CompletionStage<List<RoleConfig>> read();

    CompletionStage<RoleConfig> read(String name);

    CompletionStage<?> insert(RoleConfig roleConfig, CassandraProfile profile);

    CompletionStage<?> insertIfNotExists(RoleConfig roleConfig, CassandraProfile profile);

    CompletionStage<?> delete(String name, CassandraProfile profile);
}
