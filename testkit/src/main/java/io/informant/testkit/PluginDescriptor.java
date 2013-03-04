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

import java.util.List;

import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class PluginDescriptor {

    @JsonProperty
    @Nullable
    private String name;
    @JsonProperty
    @Nullable
    private String groupId;
    @JsonProperty
    @Nullable
    private String artifactId;
    @JsonProperty
    @Nullable
    private String version;
    @JsonProperty
    @Nullable
    private List<PropertyDescriptor> properties;
    @JsonProperty
    @Nullable
    private List<String> aspects;

    @Nullable
    String getName() {
        return name;
    }

    @Nullable
    String getGroupId() {
        return groupId;
    }

    @Nullable
    String getArtifactId() {
        return artifactId;
    }

    @Nullable
    String getVersion() {
        return version;
    }

    @Nullable
    List<PropertyDescriptor> getProperties() {
        return properties;
    }

    @Nullable
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
