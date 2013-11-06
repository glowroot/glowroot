/*
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
package io.informant.packager;

import checkers.nullness.quals.Nullable;

/**
 * This provides the binding for the plugin configuration defined in the pom.xml file.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginConfig {

    @Nullable
    private String id;
    private PropertyConfig[] properties = new PropertyConfig[0];

    @Nullable
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public PropertyConfig[] getProperties() {
        return properties;
    }

    public void setProperties(PropertyConfig[] properties) {
        this.properties = properties;
    }

    public static class PropertyConfig {
        @Nullable
        private String name;
        @Nullable
        private String defaultValue;
        @Nullable
        private String hidden;
        @Nullable
        private String prompt;
        @Nullable
        private String description;
        @Nullable
        public String getName() {
            return name;
        }
        public void setName(String name) {
            this.name = name;
        }
        @Nullable
        public String getDefault() {
            return defaultValue;
        }
        public void setDefault(String defaultValue) {
            this.defaultValue = defaultValue;
        }
        @Nullable
        public String getHidden() {
            return hidden;
        }
        public void setHidden(String hidden) {
            this.hidden = hidden;
        }
        @Nullable
        public String getPrompt() {
            return prompt;
        }
        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
        @Nullable
        public String getDescription() {
            return description;
        }
        public void setDescription(String description) {
            this.description = description;
        }
    }
}
