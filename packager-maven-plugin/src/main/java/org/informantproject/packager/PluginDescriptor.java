/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.packager;

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PluginDescriptor {

    private final String name;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final ImmutableList<PropertyDescriptor> properties;
    private final ImmutableList<String> aspects;

    public PluginDescriptor(String name, String groupId, String artifactId, String version,
            ImmutableList<PropertyDescriptor> properties, ImmutableList<String> aspects) {

        this.name = name;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.properties = properties;
        this.aspects = aspects;
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

    public List<PropertyDescriptor> getProperties() {
        return properties;
    }

    public List<String> getAspects() {
        return aspects;
    }

    @Immutable
    public static class PropertyDescriptor {
        private final String prompt;
        private final String name;
        private final String type;
        @Nullable
        private final String defaultValue;
        @Nullable
        private final String hidden;
        @Nullable
        private final String description;
        public PropertyDescriptor(String prompt, String name, String type,
                @Nullable String defaultValue, @Nullable String hidden,
                @Nullable String description) {
            this.prompt = prompt;
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
            this.hidden = hidden;
            this.description = description;
        }
        public String getPrompt() {
            return prompt;
        }
        public String getName() {
            return name;
        }
        public String getType() {
            return type;
        }
        @Nullable
        public String getDefault() {
            return defaultValue;
        }
        @Nullable
        public String getHidden() {
            return hidden;
        }
        @Nullable
        public String getDescription() {
            return description;
        }
    }
}
