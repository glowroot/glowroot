/*
 * Copyright 2014 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.OnlyUsedByTests;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

@Immutable
public class MBeanGauge {

    private final String name;
    private final String mbeanObjectName;
    private final ImmutableList<String> mbeanAttributeNames;

    private final String version;

    @VisibleForTesting
    public MBeanGauge(String name, String mbeanObjectName, List<String> mbeanAttributeNames) {
        this.name = name;
        this.mbeanObjectName = mbeanObjectName;
        this.mbeanAttributeNames = ImmutableList.copyOf(mbeanAttributeNames);
        version = VersionHashes.sha1(name, mbeanObjectName, mbeanAttributeNames);
    }

    public String getName() {
        return name;
    }

    public String getMBeanObjectName() {
        return mbeanObjectName;
    }

    public ImmutableList<String> getMBeanAttributeNames() {
        return mbeanAttributeNames;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @JsonCreator
    static MBeanGauge readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("mbeanObjectName") @Nullable String mbeanObjectName,
            @JsonProperty("mbeanAttributeNames") @Nullable List<String> mbeanAttributeNames,
            // without including a parameter for version, jackson will use direct field access after
            // this method in order to set the version field if it is included in the json being
            // deserialized (overwriting the hashed version that is calculated in the constructor)
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(name, "name");
        checkRequiredProperty(mbeanObjectName, "mbeanObjectName");
        checkRequiredProperty(mbeanAttributeNames, "mbeanAttributeNames");
        if (version != null) {
            throw new JsonMappingException("Version field is not allowed for deserialization");
        }
        return new MBeanGauge(name, mbeanObjectName, mbeanAttributeNames);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("mbeanObjectName", mbeanObjectName)
                .add("mbeanAttributeNames", mbeanAttributeNames)
                .add("version", version)
                .toString();
    }

    // the method below is only used by test harness (LocalContainer), so that tests will still
    // succeed even if core is shaded (e.g. compiled from maven) and test-harness is compiled
    // against unshaded core (e.g. compiled previously in IDE)
    //
    // don't return ImmutableList
    @OnlyUsedByTests
    @JsonIgnore
    public List<String> getMBeanAttributeNamesNeverShaded() {
        return getMBeanAttributeNames();
    }
}
