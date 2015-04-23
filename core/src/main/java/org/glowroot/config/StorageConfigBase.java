/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;

@Value.Immutable
@JsonSerialize
public abstract class StorageConfigBase {

    // currently aggregate expiration should be at least as big as trace expiration
    // errors/messages page depends on this for calculating error percentage when using the filter
    @Value.Default
    public int aggregateExpirationHours() {
        return 24 * 7;
    }

    @Value.Default
    public int traceExpirationHours() {
        return 24 * 7;
    }

    // size of capped database for storing trace details (entries and profiles)
    @Value.Default
    public int cappedDatabaseSizeMb() {
        return 1000;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }
}
