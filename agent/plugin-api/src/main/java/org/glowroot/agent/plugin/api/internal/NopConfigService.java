/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.api.internal;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.config.DoubleProperty;
import org.glowroot.agent.plugin.api.config.StringProperty;

public class NopConfigService implements ConfigService {

    public static final ConfigService INSTANCE = new NopConfigService();

    @Override
    public void registerConfigListener(ConfigListener listener) {}

    @Override
    public StringProperty getStringProperty(String name) {
        return NopStringProperty.INSTANCE;
    }

    @Override
    public BooleanProperty getBooleanProperty(String name) {
        return NopBooleanProperty.INSTANCE;
    }

    @Override
    public DoubleProperty getDoubleProperty(String name) {
        return NopDoubleProperty.INSTANCE;
    }

    private static class NopStringProperty implements StringProperty {

        private static final StringProperty INSTANCE = new NopStringProperty();

        @Override
        public String value() {
            return "";
        }
    }

    private static class NopDoubleProperty implements DoubleProperty {

        private static final DoubleProperty INSTANCE = new NopDoubleProperty();

        @Override
        public @Nullable Double value() {
            return null;
        }
    }

    private static class NopBooleanProperty implements BooleanProperty {

        private static final BooleanProperty INSTANCE = new NopBooleanProperty();

        @Override
        public boolean value() {
            return false;
        }
    }
}
