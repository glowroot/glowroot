/*
 * Copyright 2019-2023 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;

import org.glowroot.common2.repo.AgentDisplayRepository;

public class AgentDisplayRepositoryImpl implements AgentDisplayRepository {

    @Override
    public String readFullDisplay(String agentRollupId) {
        return "";
    }

    @Override
    public List<String> readDisplayParts(String agentRollupId) {
        return ImmutableList.of("");
    }

    @Override
    public CompletableFuture<String> readLastDisplayPartAsync(String agentRollupId) {
        return CompletableFuture.completedFuture("");
    }
}
