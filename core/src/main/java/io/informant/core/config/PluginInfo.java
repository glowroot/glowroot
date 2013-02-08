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
package io.informant.core.config;

import java.util.List;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PluginInfo {

    private final String name;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final ImmutableList<PropertyDescriptor> propertyDescriptors;
    // marked transient for gson serialization
    private final transient ImmutableList<String> aspects;

    PluginInfo(String name, String groupId, String artifactId, String version,
            @ReadOnly List<PropertyDescriptor> propertyDescriptors,
            @ReadOnly List<String> aspects) {
        this.name = name;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.propertyDescriptors = ImmutableList.copyOf(propertyDescriptors);
        this.aspects = ImmutableList.copyOf(aspects);
    }

    public String getId() {
        return groupId + ":" + artifactId;
    }

    public String getName() {
        return name;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public ImmutableList<PropertyDescriptor> getPropertyDescriptors() {
        return propertyDescriptors;
    }

    public ImmutableList<String> getAspects() {
        return aspects;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("groupId", groupId)
                .add("artifactId", artifactId)
                .add("version", version)
                .add("propertyDescriptors", propertyDescriptors)
                .add("aspects", aspects)
                .toString();
    }
}
