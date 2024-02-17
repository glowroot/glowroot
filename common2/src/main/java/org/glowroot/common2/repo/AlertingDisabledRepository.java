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
package org.glowroot.common2.repo;

import org.checkerframework.checker.nullness.qual.Nullable;

public interface AlertingDisabledRepository {

    // central supports alert configs on rollups
    @Nullable
    Long getAlertingDisabledUntilTime(String agentRollupId, CassandraProfile profile) throws Exception;

    // central supports alert configs on rollups
    void setAlertingDisabledUntilTime(String agentRollupId, @Nullable Long disabledUntilTime, CassandraProfile profile)
            throws Exception;
}
