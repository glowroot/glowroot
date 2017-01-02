/*
 * Copyright 2014-2017 the original author or authors.
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
package org.glowroot.common.model;

import java.util.List;

import com.google.common.collect.ImmutableList;

public class Result<T extends /*@NonNull*/ Object> {

    private final ImmutableList<T> records;
    private final boolean moreAvailable;

    public Result(List<T> records, boolean moreAvailable) {
        this.records = ImmutableList.copyOf(records);
        this.moreAvailable = moreAvailable;
    }

    public List<T> records() {
        return records;
    }

    public boolean moreAvailable() {
        return moreAvailable;
    }

    public static <T extends /*@NonNull*/ Object> Result<T> create(List<T> records, int limit) {
        if (limit != 0 && records.size() > limit) {
            return new Result<T>(records.subList(0, limit), true);
        } else {
            return new Result<T>(records, false);
        }
    }
}
