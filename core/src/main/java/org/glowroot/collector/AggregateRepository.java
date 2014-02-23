/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.collector;

import java.util.Map;

/**
 * Interface for storing aggregates.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface AggregateRepository {

    // implementations must be aware that Aggregate instances are not thread safe and cannot be
    // retained for later use
    void store(long captureTime, Aggregate overallAggregate,
            Map<String, Aggregate> transactionAggregates, Aggregate bgOverallAggregate,
            Map<String, Aggregate> bgTransactionAggregates);
}
