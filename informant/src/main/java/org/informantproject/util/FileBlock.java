/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.util;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class FileBlock {

    private final long startIndex;
    private final long length;

    public FileBlock(long startIndex, long numBytes) {
        this.startIndex = startIndex;
        this.length = numBytes;
    }

    public FileBlock(String id) throws InvalidBlockId {
        String[] parts = id.split(":");
        if (parts.length != 2) {
            throw new InvalidBlockId();
        }
        try {
            startIndex = Long.parseLong(parts[0]);
            length = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new InvalidBlockId();
        }
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
        ToStringHelper toStringHelper = Objects.toStringHelper(this)
                .add("startIndex", startIndex)
                .add("length", length);
        return toStringHelper.toString();
    }

    @SuppressWarnings("serial")
    public static class InvalidBlockId extends Exception {}
}
