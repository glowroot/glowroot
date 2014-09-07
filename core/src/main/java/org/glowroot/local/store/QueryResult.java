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
package org.glowroot.local.store;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class QueryResult<T> {

    private final List<T> records;
    private final boolean moreAvailable;

    public QueryResult(List<T> records, boolean moreAvailable) {
        this.records = records;
        this.moreAvailable = moreAvailable;
    }

    public List<T> getRecords() {
        return records;
    }

    public boolean isMoreAvailable() {
        return moreAvailable;
    }

    static <T extends /*@NonNull*/Object> QueryResult<T> from(ImmutableList<T> records, int limit) {
        if (records.size() > limit) {
            return new QueryResult<T>(records.subList(0, limit), true);
        } else {
            return new QueryResult<T>(records, false);
        }
    }

    static <T> QueryResult<T> empty() {
        return new QueryResult<T>(Lists.<T>newArrayList(), false);
    }
}
