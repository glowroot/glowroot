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
package org.glowroot.agent.plugin.kafka;

import java.util.Collections;
import java.util.List;
import java.util.Properties;

import com.google.common.base.Stopwatch;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.*;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class KafkaPluginIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = SharedSetupRunListener.getContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        SharedSetupRunListener.close(container);
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Ignore("TODO: not passing on CI, not sure why")
    @Test
    public void shouldSend() throws Exception {
        Trace trace = container.execute(SendRecord.class);
        List<Trace.Timer> nestedTimers =
                trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(nestedTimers).hasSize(1);
        assertThat(nestedTimers.get(0).getName()).isEqualTo("kafka send");
    }

    @Ignore("TODO: not passing on CI, not sure why")
    @Test
    public void shouldPoll() throws Exception {
        Trace trace = container.execute(PollRecord.class);
        List<Trace.Timer> nestedTimers =
                trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(nestedTimers).hasSize(1);
        assertThat(nestedTimers.get(0).getName()).isEqualTo("kafka poll");
    }

    public static class SendRecord implements AppUnderTest, TransactionMarker {

        private Producer<Long, String> producer;

        @Override
        public void executeApp() throws Exception {
            producer = createProducer();
            transactionMarker();
            producer.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            ProducerRecord<Long, String> record =
                    new ProducerRecord<Long, String>("demo", "message");
            producer.send(record).get();
        }

        private static Producer<Long, String> createProducer() {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "client1");
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    StringSerializer.class.getName());
            return new KafkaProducer<Long, String>(props);
        }
    }

    public static class PollRecord implements AppUnderTest, TransactionMarker {

        private Consumer<Long, String> consumer;

        @Override
        public void executeApp() throws Exception {
            Producer<Long, String> producer = SendRecord.createProducer();
            ProducerRecord<Long, String> record =
                    new ProducerRecord<Long, String>("demo", "message");
            producer.send(record).get();
            producer.close();

            consumer = createConsumer();
            transactionMarker();
            consumer.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            ConsumerRecords<Long, String> consumerRecords = consumer.poll(100);
            Stopwatch stopwatch = Stopwatch.createStarted();
            while (consumerRecords.count() == 0 && stopwatch.elapsed(SECONDS) < 5) {
                consumerRecords = consumer.poll(100);
            }
            if (consumerRecords.count() == 0) {
                throw new IllegalStateException("Record not found");
            }
            consumer.commitAsync();
        }

        private static Consumer<Long, String> createConsumer() {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "consumerGroup1");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                    LongDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    StringDeserializer.class.getName());
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            Consumer<Long, String> consumer = new KafkaConsumer<Long, String>(props);
            consumer.subscribe(Collections.singletonList("demo"));
            return consumer;
        }
    }
}
