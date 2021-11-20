/**
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.plugin.mongodb;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.Lists;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.Mongo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class MongoDbPluginIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureInsert() throws Exception {
        // when
        Trace trace = container.execute(ExecuteInsert.class);

        // then
        checkTimers(trace);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("insert testdb.test");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("mongodb query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("MongoDB");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("insert testdb.test");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureCount() throws Exception {
        // when
        Trace trace = container.execute(ExecuteCount.class);

        // then
        checkTimers(trace);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("count testdb.test");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("mongodb query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("MongoDB");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("count testdb.test");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureFind() throws Exception {
        // when
        Trace trace = container.execute(ExecuteFind.class);

        // then
        checkTimers(trace);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("find testdb.test");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("mongodb query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("MongoDB");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("find testdb.test");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    private static void checkTimers(Trace trace) {
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        List<String> timerNames = Lists.newArrayList();
        for (Trace.Timer timer : rootTimer.getChildTimerList()) {
            timerNames.add(timer.getName());
        }
        Collections.sort(timerNames);
        assertThat(timerNames).containsExactly("mongodb query");
        for (Trace.Timer timer : rootTimer.getChildTimerList()) {
            assertThat(timer.getChildTimerList()).isEmpty();
        }
        assertThat(trace.getHeader().getAsyncTimerCount()).isZero();
    }

    public static class ExecuteInsert extends DoMongoDB {
        @Override
        public void transactionMarker() {
            DB database = mongoClient.getDB("testdb");
            DBCollection collection = database.getCollection("test");
            BasicDBObject document = new BasicDBObject("test1", "test2")
                    .append("test3", "test4");
            collection.insert(document);
        }
    }

    public static class ExecuteCount extends DoMongoDB {
        @Override
        public void transactionMarker() {
            DB database = mongoClient.getDB("testdb");
            DBCollection collection = database.getCollection("test");
            collection.getCount();
        }
    }

    public static class ExecuteFind extends DoMongoDB {
        @Override
        public void transactionMarker() {
            DB database = mongoClient.getDB("testdb");
            DBCollection collection = database.getCollection("test");
            collection.find();
        }
    }

    private abstract static class DoMongoDB implements AppUnderTest, TransactionMarker {

        protected Mongo mongoClient;

        @Override
        public void executeApp() throws Exception {
            GenericContainer<?> mongo = new GenericContainer<>("mongo:4.0.3");
            mongo.setExposedPorts(Arrays.asList(27017));
            mongo.start();
            try {
                mongoClient = new Mongo(mongo.getContainerIpAddress(), mongo.getMappedPort(27017));
                transactionMarker();
            } finally {
                mongo.close();
            }
        }
    }
}
