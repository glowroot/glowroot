/**
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
package org.glowroot.plugin.jms;

import java.util.List;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class JmsPluginTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // activemq throws NPE when it is loaded inside bootstrap class loader
        container = Containers.getSharedLocalContainer();
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
    public void shouldReceiveMessage() throws Exception {
        container.executeAppUnderTest(ReceiveMessage.class);
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getTransactionType()).isEqualTo("JMS Message");
        assertThat(trace.getTransactionName()).isEqualTo("TestMessageListener");
        assertThat(trace.getHeadline()).isEqualTo("TestMessageListener.onMessage()");
    }

    @Test
    public void shouldSendMessage() throws Exception {
        container.executeAppUnderTest(SendMessage.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<String> nestedTimerNames = trace.getRootTimer().getNestedTimerNames();
        assertThat(nestedTimerNames).hasSize(1);
        assertThat(nestedTimerNames.get(0)).isEqualTo("jms send message");
    }

    public static class ReceiveMessage implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            ConnectionFactory connectionFactory =
                    new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
            Connection connection = connectionFactory.createConnection();
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("a queue");
            MessageConsumer consumer = session.createConsumer(queue);
            consumer.setMessageListener(new TestMessageListener());
            MessageProducer producer = session.createProducer(queue);
            Message message = session.createMessage();
            producer.send(message);
            Thread.sleep(1000);
        }
    }

    public static class SendMessage implements AppUnderTest, TraceMarker {

        private Connection connection;

        @Override
        public void executeApp() throws Exception {
            ConnectionFactory connectionFactory =
                    new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
            connection = connectionFactory.createConnection();
            connection.start();
            traceMarker();
        }

        @Override
        public void traceMarker() throws Exception {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("a queue");
            MessageProducer producer = session.createProducer(queue);
            Message message = session.createMessage();
            producer.send(message);
        }
    }

    static class TestMessageListener implements MessageListener {
        @Override
        public void onMessage(Message message) {}
    }
}
