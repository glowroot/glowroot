/*
 * Copyright 2011-2013 the original author or authors.
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

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StorageConfig {

    private int snapshotExpirationHours;
    private int rollingSizeMb;

    private final String version;

    public StorageConfig(String version) {
        this.version = version;
    }

    public int getSnapshotExpirationHours() {
        return snapshotExpirationHours;
    }

    public void setSnapshotExpirationHours(int snapshotExpirationHours) {
        this.snapshotExpirationHours = snapshotExpirationHours;
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
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof StorageConfig) {
            StorageConfig that = (StorageConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(snapshotExpirationHours, that.snapshotExpirationHours)
                    && Objects.equal(rollingSizeMb, that.rollingSizeMb);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(snapshotExpirationHours, rollingSizeMb);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("snapshotExpirationHours", snapshotExpirationHours)
                .add("rollingSizeMb", rollingSizeMb)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static StorageConfig readValue(
            @JsonProperty("snapshotExpirationHours") @Nullable Integer snapshotExpirationHours,
            @JsonProperty("rollingSizeMb") @Nullable Integer rollingSizeMb,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(snapshotExpirationHours, "snapshotExpirationHours");
        checkRequiredProperty(rollingSizeMb, "rollingSizeMb");
        checkRequiredProperty(version, "version");
        StorageConfig config = new StorageConfig(version);
        config.setSnapshotExpirationHours(snapshotExpirationHours);
        config.setRollingSizeMb(rollingSizeMb);
        return config;
    }
}
