/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.common.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import org.immutables.value.Value;

import org.glowroot.common.util.Versions;

import static java.util.concurrent.TimeUnit.HOURS;

@Value.Immutable
public abstract class CentralStorageConfig implements StorageConfig {

    // 3 days, 2 weeks, 3 months, 2 years
    private static final ImmutableList<Integer> DEFAULT_ROLLUP_EXPIRATION_HOURS =
            ImmutableList.of(24 * 3, 24 * 14, 24 * 90, 24 * 365 * 2);

    // TODO revisit this comment
    //
    // currently aggregate expiration should be at least as big as trace expiration
    // errors/messages page depends on this for calculating error rate when using the filter
    @Override
    @Value.Default
    public ImmutableList<Integer> rollupExpirationHours() {
        return DEFAULT_ROLLUP_EXPIRATION_HOURS;
    }

    @Override
    @Value.Default
    public int traceExpirationHours() {
        // 2 weeks
        return 24 * 14;
    }

    @Override
    @Value.Default
    public int fullQueryTextExpirationHours() {
        // 2 weeks
        return 24 * 14;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getJsonVersion(this);
    }

    public boolean hasListIssues() {
        return rollupExpirationHours().size() != DEFAULT_ROLLUP_EXPIRATION_HOURS.size();
    }

    @Value.Derived
    @JsonIgnore
    public int getMaxRollupHours() {
        int maxRollupExpirationHours = 0;
        for (int expirationHours : rollupExpirationHours()) {
            if (expirationHours == 0) {
                // zero value expiration/TTL means never expire
                return 0;
            }
            maxRollupExpirationHours = Math.max(maxRollupExpirationHours, expirationHours);
        }
        return maxRollupExpirationHours;
    }

    @Value.Derived
    @JsonIgnore
    public int getMaxRollupTTL() {
        return Ints.saturatedCast(HOURS.toSeconds(getMaxRollupHours()));
    }

    @Value.Derived
    @JsonIgnore
    public int getTraceTTL() {
        return Ints.saturatedCast(HOURS.toSeconds(traceExpirationHours()));
    }
}
