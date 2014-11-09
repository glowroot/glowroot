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
package org.glowroot.container.config;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class MBeanGauge {

    @Nullable
    private String name;
    @Nullable
    private String mbeanObjectName;
    @Nullable
    private List<String> mbeanAttributeNames;

    // null for new MBeanGauge records that haven't been sent to server yet
    @Nullable
    private final String version;

    // used to create new MBeanGauge records that haven't been sent to server yet
    public MBeanGauge() {
        version = null;
    }

    public MBeanGauge(String version) {
        this.version = version;
    }

    @Nullable
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Nullable
    public String getMBeanObjectName() {
        return mbeanObjectName;
    }

    public void setMBeanObjectName(String mbeanObjectName) {
        this.mbeanObjectName = mbeanObjectName;
    }

    @Nullable
    public List<String> getMBeanAttributeNames() {
        return mbeanAttributeNames;
    }

    public void setMBeanAttributeNames(List<String> mbeanAttributeNames) {
        this.mbeanAttributeNames = mbeanAttributeNames;
    }

    @Nullable
    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof MBeanGauge) {
            MBeanGauge that = (MBeanGauge) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(name, that.name)
                    && Objects.equal(mbeanObjectName, that.mbeanObjectName)
                    && Objects.equal(mbeanAttributeNames, that.mbeanAttributeNames);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(name, mbeanObjectName, mbeanAttributeNames);
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

    @JsonCreator
    static MBeanGauge readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("mbeanObjectName") @Nullable String mbeanObjectName,
            @JsonProperty("mbeanAttributeNames") @Nullable List<String> mbeanAttributeNames,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(name, "name");
        checkRequiredProperty(mbeanObjectName, "mbeanObjectName");
        checkRequiredProperty(mbeanAttributeNames, "mbeanAttributeNames");
        checkRequiredProperty(version, "version");
        MBeanGauge config = new MBeanGauge(version);
        config.setName(name);
        config.setMBeanObjectName(mbeanObjectName);
        config.setMBeanAttributeNames(mbeanAttributeNames);
        return config;
    }
}
