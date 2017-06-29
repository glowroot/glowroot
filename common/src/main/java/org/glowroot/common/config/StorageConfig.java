/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.common.config;

import com.google.common.collect.ImmutableList;

public interface StorageConfig {

    int AGGREGATE_QUERY_TEXT_TRUNCATE = 120; // first X characters
    int TRACE_QUERY_TEXT_TRUNCATE = 120; // first X and last X characters

    int RESOLVED_INCIDENT_EXPIRATION_HOURS = 30 * 24;

    // TODO revisit this comment
    //
    // currently aggregate expiration should be at least as big as trace expiration
    // errors/messages page depends on this for calculating error rate when using the filter
    ImmutableList<Integer> rollupExpirationHours();

    int traceExpirationHours();

    int fullQueryTextExpirationHours();
}
