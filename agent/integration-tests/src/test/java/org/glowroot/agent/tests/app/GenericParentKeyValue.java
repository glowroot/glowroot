/*
 * Copyright 2025 the original author or authors.
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
package org.glowroot.agent.tests.app;

import java.util.Map;
import java.util.HashMap;

/**
 * Generic parent class with KEY-VALUE type parameters.
 * Tests specialization with Map-like structures.
 */
public abstract class GenericParentKeyValue<K, V> {

    protected Map<K, V> storage = new HashMap<>();

    /**
     * Method with key type parameter.
     */
    public V get(K key) {
        return storage.get(key);
    }

    /**
     * Method with both key and value type parameters.
     */
    public void put(K key, V value) {
        storage.put(key, value);
    }

    /**
     * Method returning all keys.
     */
    public String listKeys() {
        return "Keys: " + storage.keySet();
    }

    /**
     * Method returning all values.
     */
    public String listValues() {
        return "Values: " + storage.values();
    }

    /**
     * Abstract method processing key-value pair.
     */
    public abstract String processEntry(K key, V value);

    /**
     * Abstract method transforming value based on key.
     */
    public abstract V transformValue(K key, V value);

    /**
     * Method checking if key exists.
     */
    public boolean containsKey(K key) {
        return storage.containsKey(key);
    }

    /**
     * Method returning size.
     */
    public int size() {
        return storage.size();
    }
}

