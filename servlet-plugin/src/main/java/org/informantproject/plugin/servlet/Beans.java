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
package org.informantproject.plugin.servlet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

import org.informantproject.shaded.google.common.cache.CacheBuilder;
import org.informantproject.shaded.google.common.cache.CacheLoader;
import org.informantproject.shaded.google.common.cache.LoadingCache;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class Beans {

    // TODO not sure if there is a retention cycle between Method and its class, so using weak keys
    // and weak values for now
    private static final LoadingCache<Class<?>, LoadingCache<String, Method>> getters = CacheBuilder
            .newBuilder().weakKeys().weakValues()
            .build(new CacheLoader<Class<?>, LoadingCache<String, Method>>() {
                @Override
                public LoadingCache<String, Method> load(final Class<?> type) {
                    return CacheBuilder.newBuilder().build(new CacheLoader<String, Method>() {
                        @Override
                        public Method load(String path) throws NoSuchMethodException {
                            // TODO fallback and look for "is" and with no prefix also
                            return type.getMethod("get" + Character.toUpperCase(path.charAt(0))
                                    + path.substring(1));
                        }
                    });
                }
            });

    private Beans() {}

    @Nullable
    public static Object value(@Nullable Object o, String[] path, int currIndex) {
        if (o == null) {
            return null;
        } else if (currIndex == path.length) {
            return o;
        } else if (o instanceof Map) {
            return value(((Map<?, ?>) o).get(path[currIndex]), path, currIndex + 1);
        } else {
            try {
                Method getter = getters.get(o.getClass()).get(path[currIndex]);
                return value(getter.invoke(o), path, currIndex + 1);
            } catch (ExecutionException e) {
                return null;
            } catch (IllegalArgumentException e) {
                return null;
            } catch (IllegalAccessException e) {
                return null;
            } catch (InvocationTargetException e) {
                return null;
            }
        }
    }
}
