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
package org.glowroot.agent.harness.aggregate;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;

import org.glowroot.agent.harness.common.HttpClient;
import org.glowroot.agent.harness.common.ObjectMappers;

import static java.util.concurrent.TimeUnit.SECONDS;

public class AggregateService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final HttpClient httpClient;

    public AggregateService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<Query> getQueries() throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 5) {
            List<Query> queries = getQueriesInternal();
            if (!queries.isEmpty()) {
                return queries;
            }
        }
        throw new AssertionError("No queries found");
    }

    private List<Query> getQueriesInternal() throws Exception {
        String content = httpClient.get("/backend/transaction/queries?server-group=&from=0&to="
                + Long.MAX_VALUE + "&transaction-type=Test+harness");
        return mapper.readValue(content, new TypeReference<List<Query>>() {});
    }
}
