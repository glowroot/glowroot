/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.collector;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public interface Collector {

    void init(File confDir, @Nullable File sharedConfDir, Environment environment,
            AgentConfig agentConfig, AgentConfigUpdater agentConfigUpdater) throws Exception;

    void collectAggregates(AggregateReader aggregateReader) throws Exception;

    void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception;

    void collectTrace(TraceReader traceReader) throws Exception;

    void log(LogEvent logEvent) throws Exception;

    interface AgentConfigUpdater {
        void update(AgentConfig agentConfig) throws IOException;
    }

    public interface AggregateReader {
        long captureTime();
        void accept(AggregateVisitor aggregateVisitor) throws Exception;
    }

    public interface TraceReader {
        long captureTime();
        String traceId();
        boolean partial();
        boolean update();
        void accept(TraceVisitor traceVisitor) throws Exception;
    }

    public interface AggregateVisitor {
        void visitOverallAggregate(String transactionType, List<String> sharedQueryTexts,
                Aggregate overallAggregate) throws Exception;
        void visitTransactionAggregate(String transactionType, String transactionName,
                List<String> sharedQueryTexts, Aggregate transactionAggregate) throws Exception;
    }

    public interface TraceVisitor extends EntryVisitor {
        void visitMainThreadProfile(Profile profile);
        void visitAuxThreadProfile(Profile profile);
        void visitHeader(Trace.Header header);
    }

    public interface EntryVisitor {
        // returns index to be used in Trace.Entry that is passed to visitEntry()
        int visitSharedQueryText(String sharedQueryText) throws Exception;
        void visitEntry(Trace.Entry entry);
    }
}
