/*
 * Copyright 2013-2014 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.MoreObjects;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

@Immutable
public class StorageConfig {

    // currently aggregate expiration should be at least as big as trace expiration
    // errors/messages page depends on this for calculating error percentage when using the filter
    private final int aggregateExpirationHours;
    private final int traceExpirationHours;
    // size of capped database for storing trace details (entries and profiles)
    private final int cappedDatabaseSizeMb;

    private final String version;

    static StorageConfig getDefault() {
        // default values should be conservative
        final int aggregateExpirationHours = 24 * 7;
        final int traceExpirationHours = 24 * 7;
        final int cappedDatabaseSizeMb = 1000;
        return new StorageConfig(aggregateExpirationHours, traceExpirationHours,
                cappedDatabaseSizeMb);
    }

    public static Overlay overlay(StorageConfig base) {
        return new Overlay(base);
    }

    private StorageConfig(int aggregateExpirationHours, int traceExpirationHours,
            int cappedDatabaseSizeMb) {
        this.aggregateExpirationHours = aggregateExpirationHours;
        this.traceExpirationHours = traceExpirationHours;
        this.cappedDatabaseSizeMb = cappedDatabaseSizeMb;
        this.version = VersionHashes.sha1(traceExpirationHours, cappedDatabaseSizeMb);
    }

    public int getAggregateExpirationHours() {
        return aggregateExpirationHours;
    }

    public int getTraceExpirationHours() {
        return traceExpirationHours;
    }

    public int getCappedDatabaseSizeMb() {
        return cappedDatabaseSizeMb;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("aggregateExpirationHours", aggregateExpirationHours)
                .add("traceExpirationHours", traceExpirationHours)
                .add("cappedDatabaseSizeMb", cappedDatabaseSizeMb)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private int aggregateExpirationHours;
        private int traceExpirationHours;
        private int cappedDatabaseSizeMb;

        private Overlay(StorageConfig base) {
            aggregateExpirationHours = base.aggregateExpirationHours;
            traceExpirationHours = base.traceExpirationHours;
            cappedDatabaseSizeMb = base.cappedDatabaseSizeMb;
        }
        public void setAggregateExpirationHours(int aggregateExpirationHours) {
            this.aggregateExpirationHours = aggregateExpirationHours;
        }
        public void setTraceExpirationHours(int traceExpirationHours) {
            this.traceExpirationHours = traceExpirationHours;
        }
        public void setCappedDatabaseSizeMb(int cappedDatabaseSizeMb) {
            this.cappedDatabaseSizeMb = cappedDatabaseSizeMb;
        }
        public StorageConfig build() {
            return new StorageConfig(aggregateExpirationHours, traceExpirationHours,
                    cappedDatabaseSizeMb);
        }
    }
}
