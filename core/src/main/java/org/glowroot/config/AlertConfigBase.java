/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.config;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@JsonSerialize
public abstract class AlertConfigBase {

    public static final Ordering<AlertConfigBase> orderingByName = new Ordering<AlertConfigBase>() {
        @Override
        public int compare(@Nullable AlertConfigBase left, @Nullable AlertConfigBase right) {
            checkNotNull(left);
            checkNotNull(right);
            return left.transactionType().compareToIgnoreCase(right.transactionType());
        }
    };

    public abstract String transactionType();
    public abstract double percentile();
    public abstract int timePeriodMinutes();
    public abstract int thresholdMillis();
    public abstract int minTransactionCount();
    public abstract ImmutableList<String> emailAddresses();

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }
}
