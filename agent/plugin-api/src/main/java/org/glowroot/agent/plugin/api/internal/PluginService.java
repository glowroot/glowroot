/*
 * Copyright 2015-2018 the original author or authors.
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

public interface PluginService {

    TimerName getTimerName(Class<?> adviceClass);

    TimerName getTimerName(String name);

    ConfigService getConfigService(String pluginId);

    <E> List<E> toImmutableList(Collection<E> elements);

    <E> Set<E> toImmutableSet(Collection<E> elements);

    <K, V> Map<K, V> toImmutableMap(Map<K, V> map);

    @Nullable
    Object getBeanValue(@Nullable Object obj, List<String> path) throws Exception;

    Map<String, String> getBeanPropertiesAsText(Object obj);
}
