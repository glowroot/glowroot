/*
 * Copyright 2014-2018 the original author or authors.
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
package org.glowroot.agent.impl;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.internal.PluginService;
import org.glowroot.agent.weaving.Beans;

public class PluginServiceImpl implements PluginService {

    private static final Logger logger = LoggerFactory.getLogger(PluginServiceImpl.class);

    private final TimerNameCache timerNameCache;

    private final LoadingCache<String, ConfigService> configServices;

    public PluginServiceImpl(TimerNameCache timerNameCache,
            final ConfigServiceFactory configServiceFactory) {
        this.timerNameCache = timerNameCache;
        configServices = CacheBuilder.newBuilder()
                .build(new CacheLoader<String, ConfigService>() {
                    @Override
                    public ConfigService load(String pluginId) {
                        return configServiceFactory.create(pluginId);
                    }
                });
    }

    @Override
    public TimerName getTimerName(Class<?> adviceClass) {
        return timerNameCache.getTimerName(adviceClass);
    }

    @Override
    public TimerName getTimerName(String name) {
        return timerNameCache.getTimerName(name);
    }

    @Override
    public ConfigService getConfigService(String pluginId) {
        return configServices.getUnchecked(pluginId);
    }

    public interface ConfigServiceFactory {
        ConfigService create(String pluginId);
    }

    @Override
    public <E> List<E> toImmutableList(Collection<E> elements) {
        return ImmutableList.copyOf(elements);
    }

    @Override
    public <E> Set<E> toImmutableSet(Collection<E> elements) {
        return ImmutableSet.copyOf(elements);
    }

    @Override
    public <K, V> Map<K, V> toImmutableMap(Map<K, V> map) {
        return ImmutableMap.copyOf(map);
    }

    @Override
    public @Nullable Object getBeanValue(@Nullable Object obj, List<String> path) throws Exception {
        return Beans.value(obj, path);
    }

    @Override
    public Map<String, String> getBeanPropertiesAsText(Object obj) {
        return Beans2.propertiesAsText(obj);
    }

    @VisibleForTesting
    static class Beans2 {

        // all getters for an individual class are only needed to handle wildcards at the end of a
        // session attribute path, e.g. "user.*"
        private static final LoadingCache<Class<?>, Map<String, Method>> wildcardGetters =
                CacheBuilder.newBuilder().weakKeys().build(new WildcardGettersCacheLoader());

        private Beans2() {}

        @VisibleForTesting
        static Map<String, String> propertiesAsText(Object obj) {
            Map<String, String> properties = Maps.newHashMap();
            Map<String, Method> allGettersForObj = wildcardGetters.getUnchecked(obj.getClass());
            for (Map.Entry<String, Method> entry : allGettersForObj.entrySet()) {
                try {
                    Object value = entry.getValue().invoke(obj);
                    if (value != null) {
                        properties.put(entry.getKey(), value.toString());
                    }
                } catch (Exception e) {
                    // log exception at debug level
                    logger.debug(e.getMessage(), e);
                    properties.put(entry.getKey(), "<could not access>");
                }
            }
            return properties;
        }

        // this unused private method is required for use as SENTINEL_METHOD above
        @SuppressWarnings("unused")
        private static void sentinelMethod() {}

        private static class WildcardGettersCacheLoader
                extends CacheLoader<Class<?>, Map<String, Method>> {
            @Override
            public Map<String, Method> load(Class<?> clazz) {
                Map<String, Method> propertyNames = Maps.newHashMap();
                for (Method method : clazz.getMethods()) {
                    String propertyName = getPropertyName(method);
                    if (propertyName == null) {
                        continue;
                    }
                    Method otherMethod = propertyNames.get(propertyName);
                    if (otherMethod != null && otherMethod.getName().startsWith("get")) {
                        // "getX" takes precedence over "isX"
                        continue;
                    }
                    method.setAccessible(true);
                    propertyNames.put(propertyName, method);
                }
                return ImmutableMap.copyOf(propertyNames);
            }

            private static @Nullable String getPropertyName(Method method) {
                if (method.getParameterTypes().length > 0) {
                    return null;
                }
                String methodName = method.getName();
                if (methodName.equals("getClass")) {
                    // ignore this "getter"
                    return null;
                }
                if (startsWithAndThenUpperCaseChar(methodName, "get")) {
                    return getRemainingWithFirstCharLowercased(methodName, "get");
                }
                if (startsWithAndThenUpperCaseChar(methodName, "is")) {
                    return getRemainingWithFirstCharLowercased(methodName, "is");
                }
                return null;
            }

            private static boolean startsWithAndThenUpperCaseChar(String str, String prefix) {
                return str.startsWith(prefix) && str.length() > prefix.length()
                        && Character.isUpperCase(str.charAt(prefix.length()));
            }

            private static String getRemainingWithFirstCharLowercased(String str, String prefix) {
                return Character.toLowerCase(str.charAt(prefix.length()))
                        + str.substring(prefix.length() + 1);
            }
        }
    }
}
