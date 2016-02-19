/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.jedis;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ConnectionIT {

    private static Container container;
    private static RedisMockServer redisMockServer;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
        redisMockServer = new RedisMockServer(6379);
        redisMockServer.start();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
        redisMockServer.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldTraceSet() throws Exception {
        // given
        // when
        Trace trace = container.execute(JedisSet.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jedis ");
    }

    @Test
    public void shouldTraceGet() throws Exception {
        // given
        // when
        Trace trace = container.execute(JedisGet.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jedis ");
    }

    @Test
    public void shouldTracePing() throws Exception {
        // given
        // when
        Trace trace = container.execute(JedisPing.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jedis ");
    }


    public static class JedisSet implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            Jedis j = new Jedis();
            j.set("key", "value");
        }
    }

    public static class JedisGet implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            Jedis j = new Jedis();
            j.get("key");
        }
    }

    public static class JedisPing implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            Jedis j = new Jedis();
            j.ping();
        }
    }



}
