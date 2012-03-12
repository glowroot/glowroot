/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.api;

import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.common.base.Preconditions;

/**
 * This map can be used to attach contextual context to a span (e.g. attaching the request or
 * session attributes to a servlet entry point). These maps are generally lazy instantiated in
 * {@link SpanDetail#getContextMap()} since they are only used for traces which end up being
 * persisted.
 * 
 * It supports String, Date, Double and Boolean values. It supports other ContextMaps as values in
 * order to nest maps. And it supports null values.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SpanContextMap implements Map<String, Optional<?>> {

    private final Map<String, Optional<?>> inner;

    public SpanContextMap() {
        this(false);
    }

    public SpanContextMap(boolean sortedByKey) {
        if (sortedByKey) {
            inner = new TreeMap<String, Optional<?>>();
        } else {
            inner = new LinkedHashMap<String, Optional<?>>();
        }
    }

    public Optional<?> putString(String key, Optional<String> value) {
        Preconditions.checkNotNull(value);
        return inner.put(key, value);
    }

    public Optional<?> putDate(String key, Optional<Date> value) {
        Preconditions.checkNotNull(value);
        return inner.put(key, value);
    }

    public Optional<?> putDouble(String key, Optional<Double> value) {
        Preconditions.checkNotNull(value);
        return inner.put(key, value);
    }

    public Optional<?> putBoolean(String key, Optional<Boolean> value) {
        Preconditions.checkNotNull(value);
        return inner.put(key, value);
    }

    public Optional<?> putMap(String key, Optional<? extends Map<String, Optional<?>>> value) {
        Preconditions.checkNotNull(value);
        if (value.isPresent()) {
            if (value.get() instanceof SpanContextMap) {
                return inner.put(key, value);
            } else {
                // build and put a SpanContextMap instead
                SpanContextMap nestedContextMap = new SpanContextMap();
                for (Entry<?, ?> entry : ((Map<?, ?>) value.get()).entrySet()) {
                    nestedContextMap.put((String) entry.getKey(), entry.getValue());
                }
                return inner.put(key, Optional.of(nestedContextMap));
            }
        } else {
            return inner.put(key, Optional.absent());
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<?> put(String key, Optional<?> value) {
        Preconditions.checkNotNull(value);
        // TODO write unit tests using each of these data types
        if (value.isPresent()) {
            if (value.get() instanceof String) {
                return putString(key, (Optional<String>) value);
            } else if (value.get() instanceof Date) {
                return putDate(key, (Optional<Date>) value);
            } else if (value.get() instanceof Number) {
                return putDouble(key, (Optional<Double>) value);
            } else if (value.get() instanceof Boolean) {
                return putBoolean(key, (Optional<Boolean>) value);
            } else if (value.get() instanceof SpanContextMap) {
                return putMap(key, (Optional<SpanContextMap>) value);
            } else {
                throw new IllegalArgumentException("Unexpected value type '"
                        + value.getClass().getName() + "'");
            }
        } else {
            return inner.put(key, Optional.absent());
        }
    }

    // convenience nullable puts

    public Object putString(String key, String value) {
        return putString(key, Optional.fromNullable(value));
    }

    public Object putDate(String key, Date value) {
        return putDate(key, Optional.fromNullable(value));
    }

    public Object putDouble(String key, Double value) {
        return putDouble(key, Optional.fromNullable(value));
    }

    public Object putBoolean(String key, Boolean value) {
        return putBoolean(key, Optional.fromNullable(value));
    }

    public Object put(String key, Object value) {
        return put(key, Optional.fromNullable(value));
    }

    public int size() {
        return inner.size();
    }

    public boolean isEmpty() {
        return inner.isEmpty();
    }

    public boolean containsKey(Object key) {
        return inner.containsKey(key);
    }

    public boolean containsValue(Object value) {
        return inner.containsValue(value);
    }

    public Optional<?> get(Object key) {
        return inner.get(key);
    }

    public Optional<?> remove(Object key) {
        return inner.remove(key);
    }

    public void putAll(Map<? extends String, ? extends Optional<?>> t) {
        // this will call put() above which is where the validation will be enforced
        inner.putAll(t);
    }

    public void clear() {
        inner.clear();
    }

    public Set<String> keySet() {
        return inner.keySet();
    }

    public Collection<Optional<?>> values() {
        return inner.values();
    }

    public Set<Entry<String, Optional<?>>> entrySet() {
        return inner.entrySet();
    }

    public static SpanContextMap of(String k1, Object v1) {
        SpanContextMap map = new SpanContextMap();
        map.put(k1, v1);
        return map;
    }

    public static SpanContextMap of(String k1, Object v1, String k2, Object v2) {
        SpanContextMap map = new SpanContextMap();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    public static SpanContextMap of(String k1, Object v1, String k2, Object v2, String k3,
            Object v3) {

        SpanContextMap map = new SpanContextMap();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return map;
    }
}
