/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.container.trace;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class TraceGcInfo {

    private final String name;
    private final long collectionCount;
    private final long collectionTime;

    private TraceGcInfo(String name, long collectionCount, long collectionTime) {
        this.name = name;
        this.collectionCount = collectionCount;
        this.collectionTime = collectionTime;
    }

    public String getName() {
        return name;
    }

    public long getCollectionCount() {
        return collectionCount;
    }

    public long getCollectionTime() {
        return collectionTime;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("collectionCount", collectionCount)
                .add("collectionTime", collectionTime)
                .toString();
    }

    @JsonCreator
    static TraceGcInfo readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("collectionCount") @Nullable Long collectionCount,
            @JsonProperty("collectionTime") @Nullable Long collectionTime)
            throws JsonMappingException {
        checkRequiredProperty(name, "name");
        checkRequiredProperty(collectionCount, "collectionCount");
        checkRequiredProperty(collectionTime, "collectionTime");
        return new TraceGcInfo(name, collectionCount, collectionTime);
    }
}
