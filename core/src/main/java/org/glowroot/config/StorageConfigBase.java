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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.immutables.value.Value;

@Value.Immutable
// ignore these old properties as part of upgrade from 0.8.3 to 0.8.4
@JsonIgnoreProperties({"aggregateExpirationHours", "gaugeExpirationHours"})
public abstract class StorageConfigBase {

    // currently aggregate expiration should be at least as big as trace expiration
    // errors/messages page depends on this for calculating error percentage when using the filter
    @Value.Default
    public ImmutableList<Integer> aggregateRollupExpirationHours() {
        // 2 days, 2 weeks, 2 months
        return ImmutableList.of(24 * 2, 24 * 14, 24 * 60);
    }

    @Value.Default
    public ImmutableList<Integer> gaugeRollupExpirationHours() {
        // 2 days, 2 weeks, 2 months
        return ImmutableList.of(24 * 2, 24 * 14, 24 * 60);
    }

    @Value.Default
    public int traceExpirationHours() {
        return 24 * 7;
    }

    @Value.Default
    public ImmutableList<Integer> aggregateDetailRollupDatabaseSizeMb() {
        return ImmutableList.of(500, 500, 500);
    }

    @Value.Default
    public int traceDetailDatabaseSizeMb() {
        return 500;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }

    boolean hasListIssues() {
        return aggregateRollupExpirationHours().size() != 3
                || gaugeRollupExpirationHours().size() != 3
                || aggregateDetailRollupDatabaseSizeMb().size() != 3;
    }

    StorageConfig withCorrectedLists() {
        StorageConfig thisConfig = (StorageConfig) this;
        StorageConfig defaultConfig = StorageConfig.builder().build();
        ImmutableList<Integer> aggregateRollupExpirationHours =
                fix(aggregateRollupExpirationHours(),
                        defaultConfig.aggregateRollupExpirationHours());
        ImmutableList<Integer> gaugeRollupExpirationHours =
                fix(gaugeRollupExpirationHours(),
                        defaultConfig.gaugeRollupExpirationHours());
        ImmutableList<Integer> aggregateDetailRollupDatabaseSizeMb =
                fix(aggregateDetailRollupDatabaseSizeMb(),
                        defaultConfig.aggregateDetailRollupDatabaseSizeMb());
        return thisConfig.withAggregateRollupExpirationHours(aggregateRollupExpirationHours)
                .withGaugeRollupExpirationHours(gaugeRollupExpirationHours)
                .withAggregateDetailRollupDatabaseSizeMb(aggregateDetailRollupDatabaseSizeMb);
    }

    private ImmutableList<Integer> fix(ImmutableList<Integer> thisList, List<Integer> defaultList) {
        if (thisList.size() >= defaultList.size()) {
            return thisList.subList(0, defaultList.size());
        }
        List<Integer> correctedList = Lists.newArrayList(thisList);
        for (int i = thisList.size(); i < defaultList.size(); i++) {
            correctedList.add(defaultList.get(i));
        }
        return ImmutableList.copyOf(correctedList);
    }
}
