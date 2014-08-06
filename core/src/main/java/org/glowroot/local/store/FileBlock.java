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

import com.google.common.base.MoreObjects;

import org.glowroot.markers.Immutable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class FileBlock {

    private final long startIndex;
    private final long length;

    static FileBlock from(long startIndex, long length) {
        return new FileBlock(startIndex, length);
    }

    static FileBlock from(String id) throws InvalidBlockIdFormatException {
        String[] parts = id.split(":");
        if (parts.length != 2) {
            throw new InvalidBlockIdFormatException();
        }
        try {
            long startIndex = Long.parseLong(parts[0]);
            long length = Long.parseLong(parts[1]);
            return new FileBlock(startIndex, length);
        } catch (NumberFormatException e) {
            throw new InvalidBlockIdFormatException(e);
        }
    }

    static FileBlock expired() {
        // startIndex == -1 is always expired since it is always before
        // CappedDatabaseOutputStream.lastCompactionBaseIndex
        return new FileBlock(-1, 0);
    }

    private FileBlock(long startIndex, long length) {
        this.startIndex = startIndex;
        this.length = length;
    }

    long getStartIndex() {
        return startIndex;
    }

    long getLength() {
        return length;
    }

    String getId() {
        return startIndex + ":" + length;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("startIndex", startIndex)
                .add("length", length)
                .toString();
    }

    @SuppressWarnings("serial")
    static class InvalidBlockIdFormatException extends Exception {
        private InvalidBlockIdFormatException() {}
        private InvalidBlockIdFormatException(Throwable cause) {
            super(cause);
        }
    }
}
