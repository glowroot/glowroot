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

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.common.Marshaling2;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@Json.Marshaled
public abstract class AlertConfig {

    public static final Ordering<AlertConfig> orderingByName = new Ordering<AlertConfig>() {
        @Override
        public int compare(@Nullable AlertConfig left, @Nullable AlertConfig right) {
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
    @Json.ForceEmpty
    public abstract List<String> emailAddresses();

    @Value.Derived
    @Json.Ignore
    public String version() {
        return Hashing.sha1().hashString(Marshaling2.toJson(this), Charsets.UTF_8).toString();
    }
}
