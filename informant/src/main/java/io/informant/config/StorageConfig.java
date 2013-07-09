/*
 * Copyright 2013 the original author or authors.
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
package io.informant.config;

import checkers.igj.quals.Immutable;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;

/**
 * Immutable structure to hold the storage config.
 * 
 * Default values should be conservative.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class StorageConfig {

    private final int snapshotExpirationHours;
    // size of fixed-length rolling database for storing trace details (spans and merged stack
    // traces)
    private final int rollingSizeMb;

    private final String version;

    static StorageConfig getDefault() {
        final int snapshotExpirationHours = 24 * 7;
        final int rollingSizeMb = 1000;
        return new StorageConfig(snapshotExpirationHours, rollingSizeMb);
    }

    public static Overlay overlay(StorageConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public StorageConfig(int snapshotExpirationHours, int rollingSizeMb) {
        this.snapshotExpirationHours = snapshotExpirationHours;
        this.rollingSizeMb = rollingSizeMb;
        this.version = VersionHashes.sha1(snapshotExpirationHours, rollingSizeMb);
    }

    public int getSnapshotExpirationHours() {
        return snapshotExpirationHours;
    }

    public int getRollingSizeMb() {
        return rollingSizeMb;
    }

    @JsonView(WithVersionJsonView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("snapshotExpirationHours", snapshotExpirationHours)
                .add("rollingSizeMb", rollingSizeMb)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    public static class Overlay {

        private int snapshotExpirationHours;
        private int rollingSizeMb;

        private Overlay(StorageConfig base) {
            snapshotExpirationHours = base.snapshotExpirationHours;
            rollingSizeMb = base.rollingSizeMb;
        }
        public void setSnapshotExpirationHours(int snapshotExpirationHours) {
            this.snapshotExpirationHours = snapshotExpirationHours;
        }
        public void setRollingSizeMb(int rollingSizeMb) {
            this.rollingSizeMb = rollingSizeMb;
        }
        public StorageConfig build() {
            return new StorageConfig(snapshotExpirationHours, rollingSizeMb);
        }
    }
}
