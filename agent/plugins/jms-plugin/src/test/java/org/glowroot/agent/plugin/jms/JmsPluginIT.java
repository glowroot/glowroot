/**
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.jms;

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

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class JmsPluginIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // activemq throws NPE when it is loaded inside bootstrap class loader
        container = Containers.createLocal();
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
        Trace trace = container.execute(ReceiveMessage.class);
        Trace.Header header = trace.getHeader();
        assertThat(header.getTransactionType()).isEqualTo("Background");
        assertThat(header.getTransactionName()).isEqualTo("JMS Message: TestMessageListener");
        assertThat(header.getHeadline()).isEqualTo("JMS Message: TestMessageListener");
    }

    @Test
    public void shouldSendMessage() throws Exception {
        Trace trace = container.execute(SendMessage.class);
        List<Trace.Timer> nestedTimers =
                trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(nestedTimers).hasSize(1);
        assertThat(nestedTimers.get(0).getName()).isEqualTo("jms send message");
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
            connection.close();
        }
    }

    public static class SendMessage implements AppUnderTest, TransactionMarker {

        private Connection connection;

        @Override
        public void executeApp() throws Exception {
            ConnectionFactory connectionFactory =
                    new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
            connection = connectionFactory.createConnection();
            connection.start();
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("a queue");
            MessageProducer producer = session.createProducer(queue);
            Message message = session.createMessage();
            producer.send(message);
            connection.close();
        }
    }

    static class TestMessageListener implements MessageListener {
        @Override
        public void onMessage(Message message) {}
    }
}
