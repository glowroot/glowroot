/*
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.agent.plugin.redis;

import java.util.Iterator;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ConnectionIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldTraceSet() throws Exception {
        // when
        Trace trace = container.execute(JedisSet.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).matches("redis localhost:\\d+ SET");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldTraceGet() throws Exception {
        // when
        Trace trace = container.execute(JedisGet.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).matches("redis localhost:\\d+ GET");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldTracePing() throws Exception {
        // when
        Trace trace = container.execute(JedisPing.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).matches("redis localhost:\\d+ PING");

        assertThat(i.hasNext()).isFalse();
    }

    private abstract static class JedisBase implements AppUnderTest, TransactionMarker {

        private RedisMockServer redisMockServer;

        private Jedis jedis;

        @Override
        public void executeApp() throws Exception {
            redisMockServer = new RedisMockServer();
            jedis = new Jedis("localhost", redisMockServer.getPort());
            transactionMarker();
            redisMockServer.close();
        }

        protected Jedis getJedis() {
            return jedis;
        }
    }

    public static class JedisSet extends JedisBase implements TransactionMarker {
        @Override
        public void transactionMarker() {
            getJedis().set("key", "value");
        }
    }

    public static class JedisGet extends JedisBase implements TransactionMarker {
        @Override
        public void transactionMarker() {
            getJedis().get("key");
        }
    }

    public static class JedisPing extends JedisBase implements TransactionMarker {
        @Override
        public void transactionMarker() {
            getJedis().ping();
        }
    }
}
