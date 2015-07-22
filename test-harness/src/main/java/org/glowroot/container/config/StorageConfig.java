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

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class StorageConfig {

    private List<Integer> aggregateRollupExpirationHours = Lists.newArrayList();
    private List<Integer> gaugeRollupExpirationHours = Lists.newArrayList();
    private int traceExpirationHours;
    private List<Integer> aggregateDetailRollupDatabaseSizeMb = Lists.newArrayList();
    private int traceDetailDatabaseSizeMb;

    private final String version;

    private StorageConfig(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public List<Integer> getAggregateRollupExpirationHours() {
        return aggregateRollupExpirationHours;
    }

    public void setAggregateRollupExpirationHours(List<Integer> aggregateRollupExpirationHours) {
        this.aggregateRollupExpirationHours = aggregateRollupExpirationHours;
    }

    public List<Integer> getGaugeRollupExpirationHours() {
        return gaugeRollupExpirationHours;
    }

    public void setGaugeRollupExpirationHours(List<Integer> gaugeRollupExpirationHours) {
        this.gaugeRollupExpirationHours = gaugeRollupExpirationHours;
    }

    public int getTraceExpirationHours() {
        return traceExpirationHours;
    }

    public void setTraceExpirationHours(int traceExpirationHours) {
        this.traceExpirationHours = traceExpirationHours;
    }

    public List<Integer> getAggregateDetailRollupDatabaseSizeMb() {
        return aggregateDetailRollupDatabaseSizeMb;
    }

    public void setAggregateDetailRollupDatabaseSizeMb(
            List<Integer> aggregateDetailRollupDatabaseSizeMb) {
        this.aggregateDetailRollupDatabaseSizeMb = aggregateDetailRollupDatabaseSizeMb;
    }

    public int getTraceDetailDatabaseSizeMb() {
        return traceDetailDatabaseSizeMb;
    }

    public void setTraceDetailDatabaseSizeMb(int traceDetailDatabaseSizeMb) {
        this.traceDetailDatabaseSizeMb = traceDetailDatabaseSizeMb;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof StorageConfig) {
            StorageConfig that = (StorageConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(aggregateRollupExpirationHours,
                    that.aggregateRollupExpirationHours)
                    && Objects.equal(gaugeRollupExpirationHours, that.gaugeRollupExpirationHours)
                    && Objects.equal(traceExpirationHours, that.traceExpirationHours)
                    && Objects.equal(aggregateDetailRollupDatabaseSizeMb,
                            that.aggregateDetailRollupDatabaseSizeMb)
                    && Objects.equal(traceDetailDatabaseSizeMb, that.traceDetailDatabaseSizeMb);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(aggregateRollupExpirationHours, gaugeRollupExpirationHours,
                traceExpirationHours, aggregateDetailRollupDatabaseSizeMb,
                traceDetailDatabaseSizeMb);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("aggregateRollupExpirationHours", aggregateRollupExpirationHours)
                .add("gaugeRollupExpirationHours", gaugeRollupExpirationHours)
                .add("traceExpirationHours", traceExpirationHours)
                .add("aggregateDetailRollupDatabaseSizeMb", aggregateDetailRollupDatabaseSizeMb)
                .add("traceDetailDatabaseSizeMb", traceDetailDatabaseSizeMb)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static StorageConfig readValue(
            @JsonProperty("aggregateRollupExpirationHours") @Nullable List<Integer> aggregateRollupExpirationHours,
            @JsonProperty("gaugeRollupExpirationHours") @Nullable List<Integer> gaugeRollupExpirationHours,
            @JsonProperty("traceExpirationHours") @Nullable Integer traceExpirationHours,
            @JsonProperty("aggregateDetailRollupDatabaseSizeMb") @Nullable List<Integer> aggregateDetailRollupDatabaseSizeMb,
            @JsonProperty("traceDetailDatabaseSizeMb") @Nullable Integer traceDetailDatabaseSizeMb,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(aggregateRollupExpirationHours, "aggregateRollupExpirationHours");
        checkRequiredProperty(gaugeRollupExpirationHours, "gaugeRollupExpirationHours");
        checkRequiredProperty(traceExpirationHours, "traceExpirationHours");
        checkRequiredProperty(aggregateDetailRollupDatabaseSizeMb,
                "aggregateDetailRollupDatabaseSizeMb");
        checkRequiredProperty(traceDetailDatabaseSizeMb, "traceDetailDatabaseSizeMb");
        checkRequiredProperty(version, "version");
        StorageConfig config = new StorageConfig(version);
        config.setAggregateRollupExpirationHours(aggregateRollupExpirationHours);
        config.setGaugeRollupExpirationHours(gaugeRollupExpirationHours);
        config.setTraceExpirationHours(traceExpirationHours);
        config.setAggregateDetailRollupDatabaseSizeMb(aggregateDetailRollupDatabaseSizeMb);
        config.setTraceDetailDatabaseSizeMb(traceDetailDatabaseSizeMb);
        return config;
    }
}
