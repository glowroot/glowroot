/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.testkit;

import static io.informant.testkit.internal.ObjectMappers.checkRequiredProperty;

import java.util.List;

import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class PluginDescriptor {

    private final String name;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final List<PropertyDescriptor> properties;
    private final List<String> aspects;

    @JsonCreator
    PluginDescriptor(@JsonProperty("name") @Nullable String name,
            @JsonProperty("groupId") @Nullable String groupId,
            @JsonProperty("artifactId") @Nullable String artifactId,
            @JsonProperty("version") @Nullable String version,
            @JsonProperty("properties") @Nullable List<PropertyDescriptor> properties,
            @JsonProperty("aspects") @Nullable List<String> aspects) throws JsonMappingException {
        checkRequiredProperty(name, "name");
        checkRequiredProperty(groupId, "groupId");
        checkRequiredProperty(artifactId, "artifactId");
        checkRequiredProperty(version, "version");
        checkRequiredProperty(properties, "properties");
        checkRequiredProperty(aspects, "aspects");
        this.name = name;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.properties = properties;
        this.aspects = aspects;
    }

    String getName() {
        return name;
    }

    String getGroupId() {
        return groupId;
    }

    String getArtifactId() {
        return artifactId;
    }

    String getVersion() {
        return version;
    }

    List<PropertyDescriptor> getProperties() {
        return properties;
    }

    List<String> getAspects() {
        return aspects;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("groupId", groupId)
                .add("artifactId", artifactId)
                .add("version", version)
                .add("properties", properties)
                .add("aspects", aspects)
                .toString();
    }
}
