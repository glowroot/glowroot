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
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * This map can be used to attach context to a span (e.g. attaching the request or session
 * attributes to a servlet entry point). These maps are generally lazy instantiated in
 * {@link Message#getContext()} since they are only used for traces which end up being persisted.
 * 
 * It supports String, Date, Double and Boolean values. It supports other ContextMaps as values in
 * order to nest maps. And it supports null values.
 * 
 * This class is not thread safe, so it should only be instantiated in response to
 * Supplier<Message>.get(), or Message.getContext() which are called by the thread that needs the
 * map.
 * 
 * This class implements all methods of Map<String, Object>, but does not implement the interface so
 * that @Nullable can be used appropriately (and users of this class cannot access it through the
 * Map interface which has different @Nullable behavior).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ContextMap {

    // map values are @Nullable
    private final Map<String, Object> inner;

    public ContextMap() {
        this(false);
    }

    public ContextMap(boolean sortedByKey) {
        if (sortedByKey) {
            inner = new TreeMap<String, Object>();
        } else {
            inner = new LinkedHashMap<String, Object>();
        }
    }

    @Nullable
    public Object putString(String key, @Nullable String value) {
        return inner.put(key, value);
    }

    @Nullable
    public Object putDate(String key, @Nullable Date value) {
        return inner.put(key, value);
    }

    @Nullable
    public Object putDouble(String key, @Nullable Double value) {
        return inner.put(key, value);
    }

    @Nullable
    public Object putBoolean(String key, @Nullable Boolean value) {
        return inner.put(key, value);
    }

    @Nullable
    public Object putMap(String key, @Nullable ContextMap value) {
        return inner.put(key, value);
    }

    @Nullable
    public Object put(String key, @Nullable Object value) {
        // TODO write unit tests using each of these data types
        if (value == null) {
            return inner.put(key, null);
        } else if (value instanceof String) {
            return putString(key, (String) value);
        } else if (value instanceof Date) {
            return putDate(key, (Date) value);
        } else if (value instanceof Double) {
            return putDouble(key, (Double) value);
        } else if (value instanceof Boolean) {
            return putBoolean(key, (Boolean) value);
        } else if (value instanceof ContextMap) {
            return putMap(key, (ContextMap) value);
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

    public boolean containsValue(@Nullable Object value) {
        return inner.containsValue(value);
    }

    public Object get(Object key) {
        return inner.get(key);
    }

    public Object remove(Object key) {
        return inner.remove(key);
    }

    public void putAll(Map<? extends String, ? extends Object> t) {
        // putAll() delegates to put() so this will still go through new entry validation
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
        // return unmodifiable view to prevent bypassing new entry validation
        return Collections.unmodifiableSet(inner.entrySet());
    }

    // this is primarily exposed for json serialization
    public Map<String, Object> getInner() {
        return Collections.unmodifiableMap(inner);
    }

    public static ContextMap of(String k1, Object v1) {
        ContextMap map = new ContextMap();
        map.put(k1, v1);
        return map;
    }

    public static ContextMap of(String k1, Object v1, String k2, Object v2) {
        ContextMap map = new ContextMap();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    public static ContextMap of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        ContextMap map = new ContextMap();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return map;
    }
}
