/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.common;

import com.google.common.collect.ImmutableList;

public final class ConfigDefaults {

    public static final ImmutableList<String> JVM_MASK_SYSTEM_PROPERTIES =
            ImmutableList.of("*password*");

    public static final String UI_DEFAULT_TRANSACTION_TYPE = "Web";
    public static final ImmutableList<Double> UI_DEFAULT_PERCENTILES =
            ImmutableList.of(50.0, 95.0, 99.0);
    public static final ImmutableList<String> UI_DEFAULT_GAUGE_NAMES =
            ImmutableList.of("java.lang:type=Memory:HeapMemoryUsage.used");

    public static final int ADVANCED_MAX_TRANSACTION_AGGREGATES = 500;
    public static final int ADVANCED_MAX_QUERY_AGGREGATES = 500;
    public static final int ADVANCED_MAX_SERVICE_CALL_AGGREGATES = 500;

    private ConfigDefaults() {}
}
