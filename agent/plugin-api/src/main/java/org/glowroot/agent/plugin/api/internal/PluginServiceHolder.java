/*
 * Copyright 2018 the original author or authors.
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.config.ConfigService;

public class PluginServiceHolder {

    private static volatile @Nullable PluginService service;
    private static volatile @Nullable Exception retrievedTooEarlyLocation;

    private PluginServiceHolder() {}

    public static PluginService get() {
        if (service == null) {
            throw new RuntimeException("Plugin service retrieved too early");
        } else {
            return service;
        }
    }

    public static void set(PluginService service) {
        PluginServiceHolder.service = service;
    }

    public static void setMockForLogger() {
        if (service == null) {
            service = new MockPluginService();
        }
    }

    private static class MockPluginService implements PluginService {

        @Override
        public TimerName getTimerName(Class<?> adviceClass) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TimerName getTimerName(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfigService getConfigService(String pluginId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <E> List<E> toImmutableList(Collection<E> elements) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <E> Set<E> toImmutableSet(Collection<E> elements) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <K, V> Map<K, V> toImmutableMap(Map<K, V> map) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable Object getBeanValue(@Nullable Object obj, List<String> path)
                throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> getBeanPropertiesAsText(Object obj) {
            throw new UnsupportedOperationException();
        }
    }
}
