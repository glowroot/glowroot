/*
 * Copyright 2011-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class StorageConfig {

    private int aggregateExpirationHours;
    private int traceExpirationHours;
    private int gaugeExpirationHours;
    private int cappedDatabaseSizeMb;

    private final String version;

    private StorageConfig(String version) {
        this.version = version;
    }

    public int getAggregateExpirationHours() {
        return aggregateExpirationHours;
    }

    public void setAggregateExpirationHours(int aggregateExpirationHours) {
        this.aggregateExpirationHours = aggregateExpirationHours;
    }

    public int getTraceExpirationHours() {
        return traceExpirationHours;
    }

    public void setTraceExpirationHours(int traceExpirationHours) {
        this.traceExpirationHours = traceExpirationHours;
    }

    public int getGaugeExpirationHours() {
        return gaugeExpirationHours;
    }

    public void setGaugeExpirationHours(int gaugeExpirationHours) {
        this.gaugeExpirationHours = gaugeExpirationHours;
    }

    public int getCappedDatabaseSizeMb() {
        return cappedDatabaseSizeMb;
    }

    public void setCappedDatabaseSizeMb(int cappedDatabaseSizeMb) {
        this.cappedDatabaseSizeMb = cappedDatabaseSizeMb;
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
            return Objects.equal(aggregateExpirationHours, that.aggregateExpirationHours)
                    && Objects.equal(traceExpirationHours, that.traceExpirationHours)
                    && Objects.equal(gaugeExpirationHours, that.gaugeExpirationHours)
                    && Objects.equal(cappedDatabaseSizeMb, that.cappedDatabaseSizeMb);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(aggregateExpirationHours, traceExpirationHours,
                gaugeExpirationHours, cappedDatabaseSizeMb);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("aggregateExpirationHours", aggregateExpirationHours)
                .add("traceExpirationHours", traceExpirationHours)
                .add("gaugeExpirationHours", gaugeExpirationHours)
                .add("cappedDatabaseSizeMb", cappedDatabaseSizeMb)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static StorageConfig readValue(
            @JsonProperty("aggregateExpirationHours") @Nullable Integer aggregateExpirationHours,
            @JsonProperty("traceExpirationHours") @Nullable Integer traceExpirationHours,
            @JsonProperty("gaugeExpirationHours") @Nullable Integer gaugeExpirationHours,
            @JsonProperty("cappedDatabaseSizeMb") @Nullable Integer cappedDatabaseSizeMb,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(aggregateExpirationHours, "aggregateExpirationHours");
        checkRequiredProperty(traceExpirationHours, "traceExpirationHours");
        checkRequiredProperty(gaugeExpirationHours, "gaugeExpirationHours");
        checkRequiredProperty(cappedDatabaseSizeMb, "cappedDatabaseSizeMb");
        checkRequiredProperty(version, "version");
        StorageConfig config = new StorageConfig(version);
        config.setAggregateExpirationHours(aggregateExpirationHours);
        config.setTraceExpirationHours(traceExpirationHours);
        config.setGaugeExpirationHours(gaugeExpirationHours);
        config.setCappedDatabaseSizeMb(cappedDatabaseSizeMb);
        return config;
    }
}
