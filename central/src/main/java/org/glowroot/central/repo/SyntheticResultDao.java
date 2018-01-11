/*
 * Copyright 2017 the original author or authors.
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

import javax.annotation.Nullable;

import org.glowroot.common.repo.SyntheticResultRepository;

public interface SyntheticResultDao extends SyntheticResultRepository {

    // synthetic result records are not rolled up to their parent, but are stored directly for
    // rollups that have their own synthetic monitors defined
    void store(String agentRollupId, String syntheticMonitorId, long captureTime,
            long durationNanos, @Nullable String errorMessage) throws Exception;

    void rollup(String agentRollupId) throws Exception;
}
