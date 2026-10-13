/*
 * Copyright 2026 the original author or authors.
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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class QueryCollectorTest {

    @Test
    public void tracksLastCaptureTime() {
        QueryCollector collector = new QueryCollector(10);
        collector.updateLastCaptureTime(100);
        collector.updateLastCaptureTime(50);
        collector.updateLastCaptureTime(200);
        assertThat(collector.getLastCaptureTime()).isEqualTo(200);
    }

    @Test
    public void mergesSameQueryByText() {
        QueryCollector collector = new QueryCollector(10);
        collector.mergeQuery("SQL", "select 1", null, 100, 1, true, 2);
        collector.mergeQuery("SQL", "select 1", null, 50, 1, true, 1);

        List<MutableQuery> queries = collector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).getTruncatedText()).isEqualTo("select 1");
        assertThat(queries.get(0).getTotalDurationNanos()).isEqualTo(150);
        assertThat(queries.get(0).getExecutionCount()).isEqualTo(2);
        assertThat(queries.get(0).getTotalRows()).isEqualTo(3);
        assertThat(queries.get(0).hasTotalRows()).isTrue();
    }

    @Test
    public void mergesSameQueryBySha1() {
        QueryCollector collector = new QueryCollector(10);
        collector.mergeQuery("SQL", "select …", "abc", 10, 1, false, 0);
        collector.mergeQuery("SQL", "select …", "abc", 20, 2, false, 0);

        List<MutableQuery> queries = collector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).getFullTextSha1()).isEqualTo("abc");
        assertThat(queries.get(0).getExecutionCount()).isEqualTo(3);
        assertThat(queries.get(0).hasTotalRows()).isFalse();
    }

    @Test
    public void sortsByTotalDurationDescending() {
        QueryCollector collector = new QueryCollector(10);
        collector.mergeQuery("SQL", "slow", null, 300, 1, false, 0);
        collector.mergeQuery("SQL", "fast", null, 10, 1, false, 0);
        collector.mergeQuery("SQL", "mid", null, 100, 1, false, 0);

        assertThat(collector.getSortedAndTruncatedQueries())
                .extracting(MutableQuery::getTruncatedText)
                .containsExactly("slow", "mid", "fast");
    }

    @Test
    public void truncatesIntoLimitExceededBucket() {
        QueryCollector collector = new QueryCollector(2);
        collector.mergeQuery("SQL", "a", null, 300, 1, false, 0);
        collector.mergeQuery("SQL", "b", null, 200, 1, false, 0);
        collector.mergeQuery("SQL", "c", null, 100, 1, false, 0);
        collector.mergeQuery("SQL", "d", null, 50, 1, false, 0);

        List<MutableQuery> queries = collector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(3);
        assertThat(queries.get(0).getTruncatedText()).isEqualTo("a");
        assertThat(queries.get(1).getTruncatedText()).isEqualTo("b");
        assertThat(queries.get(2).getTruncatedText()).isEqualTo("LIMIT EXCEEDED BUCKET");
        assertThat(queries.get(2).getTotalDurationNanos()).isEqualTo(150);
        assertThat(queries.get(2).getExecutionCount()).isEqualTo(2);
    }

    @Test
    public void mergesIncomingLimitExceededBucket() {
        QueryCollector collector = new QueryCollector(10);
        collector.mergeQuery("SQL", "LIMIT EXCEEDED BUCKET", null, 40, 4, true, 8);
        collector.mergeQuery("SQL", "LIMIT EXCEEDED BUCKET", null, 10, 1, true, 2);

        List<MutableQuery> queries = collector.getSortedAndTruncatedQueries();
        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).getTruncatedText()).isEqualTo("LIMIT EXCEEDED BUCKET");
        assertThat(queries.get(0).getTotalDurationNanos()).isEqualTo(50);
        assertThat(queries.get(0).getExecutionCount()).isEqualTo(5);
        assertThat(queries.get(0).getTotalRows()).isEqualTo(10);
    }
}
