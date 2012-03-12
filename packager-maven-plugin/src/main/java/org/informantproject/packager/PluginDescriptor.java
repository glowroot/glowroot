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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginDescriptor {

    private final String name;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final List<PropertyDescriptor> properties;

    public PluginDescriptor(String name, String groupId, String artifactId, String version,
            List<PropertyDescriptor> properties) {

        this.name = name;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.properties = properties;
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

    public static class PropertyDescriptor {
        private final String prompt;
        private final String name;
        private final String type;
        private final String defaultValue;
        private final String hidden;
        private final String description;
        public PropertyDescriptor(String prompt, String name, String type, String defaultValue,
                String hidden, String description) {
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
        public String getDefault() {
            return defaultValue;
        }
        public String getHidden() {
            return hidden;
        }
        public String getDescription() {
            return description;
        }
    }
}
