/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.dist;

import javax.annotation.Nullable;

public class PluginConfig {

    private @Nullable String id;
    private PropertyConfig[] properties = new PropertyConfig[0];

    public @Nullable String getId() {
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

        private @Nullable String name;
        private @Nullable String defaultValue;
        private @Nullable String description;

        public @Nullable String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public @Nullable String getDefault() {
            return defaultValue;
        }

        public void setDefault(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public @Nullable String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
