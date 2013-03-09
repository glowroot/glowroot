/**
 * Copyright 2013 the original author or authors.
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
package io.informant.util;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

/**
 * Additional method similar to those in Guava's {@link Hashing} class.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class Hashing2 {

    private static final Logger logger = LoggerFactory.getLogger(Hashing2.class);
    private static final long DELIMITER = 123454321;

    private Hashing2() {}

    public static String sha1(@Nullable Object... objects) {
        return hashCode(Hashing.sha1(), objects);
    }

    public static String hashCode(HashFunction function, @Nullable Object... objects) {
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
            hasher.putString((String) object);
        } else if (object instanceof Enum<?>) {
            hasher.putString(((Enum<?>) object).name());
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
