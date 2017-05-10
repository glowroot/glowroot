/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.init;

import java.util.List;

import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.collector.Collector.AggregateReader;
import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;

import static org.mockito.Mockito.mock;

public class CollectorProxyTest {

    @Test
    public void testCollectAggregates() throws Exception {
        // given
        CollectorProxy collectorProxy = new CollectorProxy();
        AggregateReader aggregateReader1 = mock(AggregateReader.class);
        AggregateReader aggregateReader2 = mock(AggregateReader.class);
        AggregateReader aggregateReader3 = mock(AggregateReader.class);

        // when
        collectorProxy.collectAggregates(aggregateReader1);
        collectorProxy.collectAggregates(aggregateReader2);
        Collector collector = mock(Collector.class);
        collectorProxy.setInstance(collector);
        collectorProxy.collectAggregates(aggregateReader3);

        // then
        InOrder inOrder = Mockito.inOrder(collector);
        inOrder.verify(collector).collectAggregates(aggregateReader1);
        inOrder.verify(collector).collectAggregates(aggregateReader2);
        inOrder.verify(collector).collectAggregates(aggregateReader3);
    }

    @Test
    public void testCollectGaugeValues() throws Exception {
        // given
        CollectorProxy collectorProxy = new CollectorProxy();
        @SuppressWarnings("unchecked")
        List<GaugeValue> gaugeValues1 = mock(List.class);
        @SuppressWarnings("unchecked")
        List<GaugeValue> gaugeValues2 = mock(List.class);
        @SuppressWarnings("unchecked")
        List<GaugeValue> gaugeValues3 = mock(List.class);

        // when
        collectorProxy.collectGaugeValues(gaugeValues1);
        collectorProxy.collectGaugeValues(gaugeValues2);
        Collector collector = mock(Collector.class);
        collectorProxy.setInstance(collector);
        collectorProxy.collectGaugeValues(gaugeValues3);

        // then
        InOrder inOrder = Mockito.inOrder(collector);
        inOrder.verify(collector).collectGaugeValues(gaugeValues1);
        inOrder.verify(collector).collectGaugeValues(gaugeValues2);
        inOrder.verify(collector).collectGaugeValues(gaugeValues3);
    }

    @Test
    public void testCollectTrace() throws Exception {
        // given
        CollectorProxy collectorProxy = new CollectorProxy();
        TraceReader traceReader1 = mock(TraceReader.class);
        TraceReader traceReader2 = mock(TraceReader.class);
        TraceReader traceReader3 = mock(TraceReader.class);

        // when
        collectorProxy.collectTrace(traceReader1);
        collectorProxy.collectTrace(traceReader2);
        Collector collector = mock(Collector.class);
        collectorProxy.setInstance(collector);
        collectorProxy.collectTrace(traceReader3);

        // then
        InOrder inOrder = Mockito.inOrder(collector);
        inOrder.verify(collector).collectTrace(traceReader1);
        inOrder.verify(collector).collectTrace(traceReader2);
        inOrder.verify(collector).collectTrace(traceReader3);
    }

    @Test
    public void testLog() throws Exception {
        // given
        CollectorProxy collectorProxy = new CollectorProxy();
        LogEvent logEvent1 = LogEvent.newBuilder()
                .setMessage("one")
                .build();
        LogEvent logEvent2 = LogEvent.newBuilder()
                .setMessage("two")
                .build();
        LogEvent logEvent3 = LogEvent.newBuilder()
                .setMessage("three")
                .build();

        // when
        collectorProxy.log(logEvent1);
        collectorProxy.log(logEvent2);
        Collector collector = mock(Collector.class);
        collectorProxy.setInstance(collector);
        collectorProxy.log(logEvent3);

        // then
        InOrder inOrder = Mockito.inOrder(collector);
        inOrder.verify(collector).log(logEvent1);
        inOrder.verify(collector).log(logEvent2);
        inOrder.verify(collector).log(logEvent3);
    }
}
