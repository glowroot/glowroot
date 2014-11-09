/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.local.store;

import org.immutables.value.Value;

@Value.Immutable
abstract class FileBlock {

    @Value.Parameter
    abstract long startIndex();
    @Value.Parameter
    abstract long length();

    String getId() {
        return startIndex() + ":" + length();
    }

    static FileBlock from(String id) throws InvalidBlockIdFormatException {
        String[] parts = id.split(":");
        if (parts.length != 2) {
            throw new InvalidBlockIdFormatException();
        }
        try {
            long startIndex = Long.parseLong(parts[0]);
            long length = Long.parseLong(parts[1]);
            return ImmutableFileBlock.of(startIndex, length);
        } catch (NumberFormatException e) {
            throw new InvalidBlockIdFormatException(e);
        }
    }

    static FileBlock expired() {
        // startIndex == -1 is always expired since it is always before
        // CappedDatabaseOutputStream.lastCompactionBaseIndex
        return ImmutableFileBlock.of(-1, 0);
    }

    @SuppressWarnings("serial")
    static class InvalidBlockIdFormatException extends Exception {
        private InvalidBlockIdFormatException() {}
        private InvalidBlockIdFormatException(Throwable cause) {
            super(cause);
        }
    }
}
