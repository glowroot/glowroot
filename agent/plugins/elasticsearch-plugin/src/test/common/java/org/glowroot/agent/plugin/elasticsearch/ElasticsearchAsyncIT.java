/**
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.agent.plugin.elasticsearch;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.Lists;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(ElasticsearchExtension.class)
public class ElasticsearchAsyncIT {

    private static Container container;

    @BeforeAll
    public static void setUp() {
        container = ElasticsearchExtension.getContainer();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureDocumentPut() throws Exception {
        // when
        Trace trace = container.execute(ExecuteDocumentPut.class);

        // then
        checkTimers(trace, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("PUT testindex/testtype");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("elasticsearch query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("PUT testindex/testtype");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureDocumentGet() throws Exception {
        // when
        Trace trace = container.execute(ExecuteDocumentGet.class);

        // then
        checkTimers(trace, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("GET testindex/testtype");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("elasticsearch query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).startsWith(" [");

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("GET testindex/testtype");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldConcurrentlyAsyncExecuteSameStatement() throws Exception {
        // when
        Trace trace = container.execute(ConcurrentlyExecuteSameAsyncStatement.class);

        // then
        checkTimers(trace, 100);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        for (int j = 0; j < 100; j++) {
            Trace.Entry entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(0);
            assertThat(entry.getMessage()).isEmpty();
            assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                    .getFullText()).isEqualTo("GET testindex/testtype");
            assertThat(entry.getQueryEntryMessage().getPrefix())
                    .isEqualTo("elasticsearch query: ");
            assertThat(entry.getQueryEntryMessage().getSuffix()).startsWith(" [");
        }
        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("GET testindex/testtype");
        assertThat(query.getExecutionCount()).isEqualTo(100);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureDocumentUpdate() throws Exception {
        // when
        Trace trace = container.execute(ExecuteDocumentUpdate.class);

        // then
        checkTimers(trace, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("PUT testindex/testtype");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("elasticsearch query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).startsWith(" [");

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("PUT testindex/testtype");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureDocumentDelete() throws Exception {
        // when
        Trace trace = container.execute(ExecuteDocumentDelete.class);

        // then
        checkTimers(trace, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("DELETE testindex/testtype");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("elasticsearch query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).startsWith(" [");

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("DELETE testindex/testtype");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureDocumentSearchWithoutSource() throws Exception {
        // when
        Trace trace = container.execute(ExecuteDocumentSearchWithoutSource.class);

        // then
        checkTimers(trace, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).startsWith("SEARCH testindex/testtype {");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("elasticsearch query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .startsWith("SEARCH testindex/testtype {");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureDocumentSearchWithoutIndexesWithoutSource() throws Exception {
        // when
        Trace trace = container.execute(ExecuteDocumentSearchWithoutIndexesWithoutSource.class);

        // then
        checkTimers(trace, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).startsWith("SEARCH _any/testtype {");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("elasticsearch query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .startsWith("SEARCH _any/testtype {");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureDocumentSearchWithoutIndexesWithoutTypesWithoutSource()
            throws Exception {
        // when
        Trace trace = container
                .execute(ExecuteDocumentSearchWithoutIndexesWithoutTypesWithoutSource.class);

        // then
        checkTimers(trace, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).startsWith("SEARCH / {");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("elasticsearch query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .startsWith("SEARCH / {");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureDocumentSearchWithMultipleIndexesWithMultipleTypesWithoutSource()
            throws Exception {
        // when
        Trace trace = container.execute(
                ExecuteDocumentSearchWithMultipleIndexesWithMultipleTypesWithoutSource.class);

        // then
        checkTimers(trace, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).startsWith("SEARCH testindex,testindex2/testtype,testtype2 {");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("elasticsearch query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .startsWith("SEARCH testindex,testindex2/testtype,testtype2 {");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureDocumentSearchWithSource() throws Exception {
        // when
        Trace trace = container.execute(ExecuteDocumentSearchWithSource.class);

        // then
        checkTimers(trace, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).startsWith("SEARCH testindex/testtype {");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("elasticsearch query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("Elasticsearch");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .startsWith("SEARCH testindex/testtype {");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    private static void checkTimers(Trace trace, int count) {
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        List<String> timerNames = Lists.newArrayList();
        for (Trace.Timer timer : rootTimer.getChildTimerList()) {
            timerNames.add(timer.getName());
        }
        Collections.sort(timerNames);
        assertThat(timerNames).containsExactly("elasticsearch query");
        for (Trace.Timer timer : rootTimer.getChildTimerList()) {
            assertThat(timer.getChildTimerList()).isEmpty();
        }
        assertThat(trace.getHeader().getAsyncTimerCount()).isEqualTo(1);
        Trace.Timer asyncTimer = trace.getHeader().getAsyncTimer(0);
        assertThat(asyncTimer.getChildTimerCount()).isZero();
        assertThat(asyncTimer.getName()).isEqualTo("elasticsearch query");
        assertThat(asyncTimer.getCount()).isEqualTo(count);
    }

    public static class ExecuteDocumentPut implements AppUnderTest, TransactionMarker {

        private TransportClient client;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .execute()
                    .get();
        }
    }

    public static class ExecuteDocumentGet implements AppUnderTest, TransactionMarker {

        private TransportClient client;
        private String documentId;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            IndexResponse response = client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            documentId = response.getId();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareGet("testindex", "testtype", documentId)
                    .execute()
                    .get();
        }
    }

    public static class ConcurrentlyExecuteSameAsyncStatement
            implements AppUnderTest, TransactionMarker {

        private TransportClient client;
        private String documentId;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            IndexResponse response = client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            documentId = response.getId();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            List<ActionFuture<GetResponse>> futures = Lists.newArrayList();
            for (int i = 0; i < 100; i++) {
                futures.add(client.prepareGet("testindex", "testtype", documentId)
                        .execute());
            }
            for (ActionFuture<GetResponse> future : futures) {
                future.get();
            }
        }
    }

    public static class ExecuteDocumentUpdate implements AppUnderTest, TransactionMarker {

        private TransportClient client;
        private String documentId;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            IndexResponse response = client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            documentId = response.getId();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareUpdate("testindex", "testtype", documentId)
                    .setDoc("xyz", "some updated text")
                    .execute()
                    .get();
        }
    }

    public static class ExecuteDocumentDelete implements AppUnderTest, TransactionMarker {

        private TransportClient client;
        private String documentId;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            IndexResponse response = client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            documentId = response.getId();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareDelete("testindex", "testtype", documentId)
                    .execute()
                    .get();
        }
    }

    public static class ExecuteDocumentSearchWithoutSource
            implements AppUnderTest, TransactionMarker {

        private TransportClient client;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareSearch("testindex")
                    .setTypes("testtype")
                    .execute()
                    .get();
        }
    }

    public static class ExecuteDocumentSearchWithoutIndexesWithoutSource
            implements AppUnderTest, TransactionMarker {

        private TransportClient client;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareSearch()
                    .setTypes("testtype")
                    .execute()
                    .get();
        }
    }

    public static class ExecuteDocumentSearchWithoutTypesWithoutSource
            implements AppUnderTest, TransactionMarker {

        private TransportClient client;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareSearch("testindex")
                    .execute()
                    .get();
        }
    }

    public static class ExecuteDocumentSearchWithoutIndexesWithoutTypesWithoutSource
            implements AppUnderTest, TransactionMarker {

        private TransportClient client;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareSearch()
                    .execute()
                    .get();
        }
    }

    public static class ExecuteDocumentSearchWithMultipleIndexesWithMultipleTypesWithoutSource
            implements AppUnderTest, TransactionMarker {

        private TransportClient client;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            client.prepareIndex("testindex2", "testtype2")
                    .setSource("abc2", 11, "xyz2", "some text")
                    .get();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareSearch("testindex", "testindex2")
                    .setTypes("testtype", "testtype2")
                    .execute()
                    .get();
        }
    }

    public static class ExecuteDocumentSearchWithSource implements AppUnderTest, TransactionMarker {

        private TransportClient client;

        @Override
        public void executeApp() throws Exception {
            client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
            client.prepareIndex("testindex", "testtype")
                    .setSource("abc", 11, "xyz", "some text")
                    .get();
            transactionMarker();
            client.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.prepareSearch("testindex")
                    .setTypes("testtype")
                    .setQuery(QueryBuilders.termQuery("xyz", "text"))
                    .execute()
                    .get();
        }
    }
}
