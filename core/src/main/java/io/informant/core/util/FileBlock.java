/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.core.util;

import checkers.igj.quals.Immutable;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class FileBlock {

    private final long startIndex;
    private final long length;

    public static FileBlock from(long startIndex, long length) {
        return new FileBlock(startIndex, length);
    }

    public static FileBlock from(String id) throws InvalidBlockIdFormatException {
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

    private FileBlock(long startIndex, long length) {
        this.startIndex = startIndex;
        this.length = length;
    }

    public long getStartIndex() {
        return startIndex;
    }

    public long getLength() {
        return length;
    }

    public String getId() {
        return startIndex + ":" + length;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("startIndex", startIndex)
                .add("length", length)
                .toString();
    }

    @SuppressWarnings("serial")
    public static class InvalidBlockIdFormatException extends Exception {
        private InvalidBlockIdFormatException() {}
        private InvalidBlockIdFormatException(Throwable cause) {
            super(cause);
        }
    }
}
