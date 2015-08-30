/*
 * Copyright 2014-2015 the original author or authors.
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

public class GarbageCollectorActivity {

    private final String collectorName;
    private final long collectionCount;
    private final long collectionTimeMillis;

    private GarbageCollectorActivity(String collectorName, long collectionCount,
            long collectionTimeMillis) {
        this.collectorName = collectorName;
        this.collectionCount = collectionCount;
        this.collectionTimeMillis = collectionTimeMillis;
    }

    public String getCollectorName() {
        return collectorName;
    }

    public long getCollectionCount() {
        return collectionCount;
    }

    public long getCollectionTimeMillis() {
        return collectionTimeMillis;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("collectorName", collectorName)
                .add("collectionCount", collectionCount)
                .add("collectionTimeMillis", collectionTimeMillis)
                .toString();
    }

    @JsonCreator
    static GarbageCollectorActivity readValue(
            @JsonProperty("collectorName") @Nullable String collectorName,
            @JsonProperty("collectionCount") @Nullable Long collectionCount,
            @JsonProperty("collectionTimeMillis") @Nullable Long collectionTimeMillis)
                    throws JsonMappingException {
        checkRequiredProperty(collectorName, "collectorName");
        checkRequiredProperty(collectionCount, "collectionCount");
        checkRequiredProperty(collectionTimeMillis, "collectionTimeMillis");
        return new GarbageCollectorActivity(collectorName, collectionCount, collectionTimeMillis);
    }
}
