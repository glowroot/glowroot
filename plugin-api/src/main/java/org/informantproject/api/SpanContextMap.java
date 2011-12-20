/**
 * Copyright 2011 the original author or authors.
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
public class SpanContextMap implements Map<String, Object> {

    private final Map<String, Object> inner;

    public SpanContextMap() {
        this(false);
    }

    public SpanContextMap(boolean sortedByKey) {
        if (sortedByKey) {
            inner = new TreeMap<String, Object>();
        } else {
            inner = new LinkedHashMap<String, Object>();
        }
    }

    public Object putString(String key, String value) {
        return inner.put(key, value);
    }

    public Object putDate(String key, Date value) {
        return inner.put(key, value);
    }

    public Object putDouble(String key, Double value) {
        return inner.put(key, value);
    }

    public Object putBoolean(String key, Boolean value) {
        return inner.put(key, value);
    }

    public Object putMap(String key, Map<String, Object> value) {
        if (value == null) {
            return inner.put(key, null);
        } else if (value instanceof SpanContextMap) {
            return inner.put(key, value);
        } else {
            // build and put a SpanContextMap instead
            SpanContextMap nestedContextMap = new SpanContextMap();
            for (Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                nestedContextMap.put((String) entry.getKey(), entry.getValue());
            }
            return inner.put(key, nestedContextMap);
        }
    }

    public Object put(String key, Object value) {
        // TODO write unit tests using each of these data types
        if (value == null) {
            return inner.put(key, null);
        } else if (value instanceof String) {
            return putString(key, (String) value);
        } else if (value instanceof Date) {
            return putDate(key, (Date) value);
        } else if (value instanceof Number) {
            return putDouble(key, (Double) value);
        } else if (value instanceof Boolean) {
            return putBoolean(key, (Boolean) value);
        } else if (value instanceof SpanContextMap) {
            return putMap(key, (SpanContextMap) value);
        } else {
            throw new IllegalArgumentException("Unexpected value type '"
                    + value.getClass().getName() + "'");
        }
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

    public Object get(Object key) {
        return inner.get(key);
    }

    public Object remove(Object key) {
        return inner.remove(key);
    }

    public void putAll(Map<? extends String, ? extends Object> t) {
        // this will call put() above which is where the validation will be enforced
        inner.putAll(t);
    }

    public void clear() {
        inner.clear();
    }

    public Set<String> keySet() {
        return inner.keySet();
    }

    public Collection<Object> values() {
        return inner.values();
    }

    public Set<Entry<String, Object>> entrySet() {
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
