/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage;

import com.google.common.base.Strings;

/**
 * Central persistence backend. Cassandra remains the default and fully supported path.
 * {@link #POSTGRES} is experimental (spike / incomplete).
 */
public enum StorageType {

    CASSANDRA,
    POSTGRES;

    public static StorageType parse(/*@Nullable*/ String raw) {
        if (Strings.isNullOrEmpty(raw)) {
            return CASSANDRA;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "cassandra":
                return CASSANDRA;
            case "postgres":
            case "postgresql":
                return POSTGRES;
            default:
                throw new IllegalStateException(
                        "Invalid storage.type value '" + raw + "', expected cassandra or postgres");
        }
    }
}
