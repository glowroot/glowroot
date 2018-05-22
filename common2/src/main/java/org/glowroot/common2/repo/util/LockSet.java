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
package org.glowroot.common2.repo.util;

import java.io.Serializable;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import org.checkerframework.checker.nullness.qual.Nullable;

public interface LockSet<K extends /*@NonNull*/ Serializable> {

    @Nullable
    UUID lock(K key);

    void unlock(K key, UUID lockToken);

    public static class LockSetImpl<K extends /*@NonNull*/ Serializable> implements LockSet<K> {

        private final ConcurrentMap<K, UUID> map;

        public LockSetImpl(ConcurrentMap<K, UUID> map) {
            this.map = map;
        }

        @Override
        public @Nullable UUID lock(K key) {
            UUID lockToken = UUID.randomUUID();
            if (map.putIfAbsent(key, lockToken) == null) {
                return lockToken;
            } else {
                return null;
            }
        }

        @Override
        public void unlock(K key, UUID lockToken) {
            map.remove(key, lockToken);
        }
    }
}
