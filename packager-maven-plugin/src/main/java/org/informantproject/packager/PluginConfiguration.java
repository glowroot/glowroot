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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginConfiguration {

    private String groupId;
    private String artifactId;
    private PropertyConfiguration[] properties;

    public String getId() {
        return groupId + ":" + artifactId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public PropertyConfiguration[] getProperties() {
        return properties;
    }

    public void setProperties(PropertyConfiguration[] properties) {
        this.properties = properties;
    }

    public static class PropertyConfiguration {
        private String prompt;
        private String name;
        private String defaultValue;
        private String hidden;
        private String description;
        public String getPrompt() {
            return prompt;
        }
        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
        public String getName() {
            return name;
        }
        public void setName(String name) {
            this.name = name;
        }
        public String getDefault() {
            return defaultValue;
        }
        public void setDefault(String defaultValue) {
            this.defaultValue = defaultValue;
        }
        public String getHidden() {
            return hidden;
        }
        public void setHidden(String hidden) {
            this.hidden = hidden;
        }
        public String getDescription() {
            return description;
        }
        public void setDescription(String description) {
            this.description = description;
        }
    }
}
