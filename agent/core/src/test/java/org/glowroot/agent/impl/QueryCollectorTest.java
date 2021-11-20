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
package org.glowroot.agent.impl;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.model.QueryCollector;
import org.glowroot.agent.model.SharedQueryTextCollection;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

import static org.assertj.core.api.Assertions.assertThat;

public class QueryCollectorTest {

    @Test
    public void testAddInAscendingOrder() throws Exception {
        QueryCollector queries = new QueryCollector(100, 4);
        for (int i = 1; i <= 300; i++) {
            queries.mergeQuery("SQL", Integer.toString(i), i, 1, true, 1, false);
        }
        test(queries);
    }

    @Test
    public void testAddInDescendingOrder() throws Exception {
        QueryCollector queries = new QueryCollector(100, 4);
        for (int i = 300; i > 0; i--) {
            queries.mergeQuery("SQL", Integer.toString(i), i, 1, true, 1, false);
        }
        test(queries);
    }

    private void test(QueryCollector collector) throws Exception {
        // when
        SharedQueryTextCollectionImpl sharedQueryTextCollection =
                new SharedQueryTextCollectionImpl();
        List<Aggregate.Query> queries =
                collector.toAggregateProto(sharedQueryTextCollection, false);
        // then
        assertThat(queries).hasSize(101);
        Aggregate.Query topQuery = queries.get(0);
        assertThat(
                sharedQueryTextCollection.sharedQueryTexts.get(topQuery.getSharedQueryTextIndex()))
                        .isEqualTo("LIMIT EXCEEDED BUCKET");
        int limitExceededBucketTotalNanos = 0;
        for (int i = 1; i <= 200; i++) {
            limitExceededBucketTotalNanos += i;
        }
        assertThat(topQuery.getTotalDurationNanos()).isEqualTo(limitExceededBucketTotalNanos);
        assertThat(queries.get(1).getTotalDurationNanos()).isEqualTo(300);
        assertThat(queries.get(100).getTotalDurationNanos()).isEqualTo(201);
    }

    private static class SharedQueryTextCollectionImpl implements SharedQueryTextCollection {

        private final Map<String, Integer> sharedQueryTextIndexes = Maps.newHashMap();
        private List<String> sharedQueryTexts = Lists.newArrayList();

        @Override
        public int getSharedQueryTextIndex(String queryText) {
            Integer sharedQueryTextIndex = sharedQueryTextIndexes.get(queryText);
            if (sharedQueryTextIndex == null) {
                sharedQueryTextIndex = sharedQueryTextIndexes.size();
                sharedQueryTextIndexes.put(queryText, sharedQueryTextIndex);
                sharedQueryTexts.add(queryText);
            }
            return sharedQueryTextIndex;
        }
    }
}
