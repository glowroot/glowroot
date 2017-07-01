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
package org.glowroot.ui;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Lists;

class DataSeries {

    // null is used for 'Other' data series
    @JsonProperty
    private final @Nullable String name;
    @JsonProperty
    private final List<Number /*@Nullable*/ []> data = Lists.newArrayList();
    @JsonProperty
    private @Nullable Double overall;

    DataSeries(@Nullable String name) {
        this.name = name;
    }

    @Nullable
    String getName() {
        return name;
    }

    void add(long captureTime, double value) {
        data.add(new Number[] {captureTime, value});
    }

    void addNull() {
        data.add(null);
    }

    public void setOverall(double overall) {
        this.overall = overall;
    }
}
