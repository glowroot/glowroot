/*
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
package org.glowroot.wire.api;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.SystemInfo;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public interface Collector {

    void init(File glowrootBaseDir, SystemInfo systemInfo, AgentConfig agentConfig,
            AgentConfigUpdater agentConfigUpdater) throws Exception;

    void collectAggregates(long captureTime, List<AggregatesByType> aggregatesByType)
            throws Exception;

    void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception;

    void collectTrace(Trace trace) throws Exception;

    void log(LogEvent logEvent) throws Exception;

    interface AgentConfigUpdater {
        void update(AgentConfig agentConfig) throws IOException;
    }
}
