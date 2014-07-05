/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.config;

import java.util.List;

import com.google.common.base.Charsets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class VersionHashes {

    private static final Logger logger = LoggerFactory.getLogger(VersionHashes.class);
    private static final long DELIMITER = 123454321;

    private VersionHashes() {}

    public static String sha1(@Nullable Object... objects) {
        return hashCode(Hashing.sha1(), objects);
    }

    private static String hashCode(HashFunction function, @Nullable Object... objects) {
        Hasher hasher = function.newHasher();
        for (Object object : objects) {
            putObject(hasher, object);
        }
        return hasher.hash().toString();
    }

    private static void putObject(Hasher hasher, @Nullable Object object) {
        if (object == null) {
            // put nothing
        } else if (object instanceof Integer) {
            hasher.putInt((Integer) object);
        } else if (object instanceof Long) {
            hasher.putLong((Long) object);
        } else if (object instanceof Double) {
            hasher.putDouble((Double) object);
        } else if (object instanceof Boolean) {
            hasher.putBoolean((Boolean) object);
        } else if (object instanceof String) {
            hasher.putString((String) object, Charsets.UTF_8);
        } else if (object instanceof Enum<?>) {
            hasher.putString(((Enum<?>) object).name(), Charsets.UTF_8);
        } else if (object instanceof List<?>) {
            List<?> items = (List<?>) object;
            for (Object item : items) {
                putObject(hasher, item);
            }
        } else {
            logger.error("unsupported object type: {}", object.getClass());
        }
        // put delimiter after every entry
        // see https://code.google.com/p/guava-libraries/wiki/HashingExplained
        hasher.putLong(DELIMITER);
    }
}
