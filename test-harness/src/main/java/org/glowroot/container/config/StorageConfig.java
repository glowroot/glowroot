/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container.config;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import dataflow.quals.Pure;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StorageConfig {

    private int traceExpirationHours;
    private int rollingSizeMb;

    private final String version;

    public StorageConfig(String version) {
        this.version = version;
    }

    public int getTraceExpirationHours() {
        return traceExpirationHours;
    }

    public void setTraceExpirationHours(int traceExpirationHours) {
        this.traceExpirationHours = traceExpirationHours;
    }

    public int getRollingSizeMb() {
        return rollingSizeMb;
    }

    public void setRollingSizeMb(int rollingSizeMb) {
        this.rollingSizeMb = rollingSizeMb;
    }

    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof StorageConfig) {
            StorageConfig that = (StorageConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(traceExpirationHours, that.traceExpirationHours)
                    && Objects.equal(rollingSizeMb, that.rollingSizeMb);
        }
        return false;
    }

    @Override
    @Pure
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(traceExpirationHours, rollingSizeMb);
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("traceExpirationHours", traceExpirationHours)
                .add("rollingSizeMb", rollingSizeMb)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static StorageConfig readValue(
            @JsonProperty("traceExpirationHours") @Nullable Integer traceExpirationHours,
            @JsonProperty("rollingSizeMb") @Nullable Integer rollingSizeMb,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(traceExpirationHours, "traceExpirationHours");
        checkRequiredProperty(rollingSizeMb, "rollingSizeMb");
        checkRequiredProperty(version, "version");
        StorageConfig config = new StorageConfig(version);
        config.setTraceExpirationHours(traceExpirationHours);
        config.setRollingSizeMb(rollingSizeMb);
        return config;
    }
}
