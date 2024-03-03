/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.common2.repo;

import java.util.List;
import java.util.concurrent.CompletionStage;

import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;

public interface GaugeValueRepository {

    CompletionStage<List<Gauge>> getRecentlyActiveGauges(String agentRollupId);

    CompletionStage<List<Gauge>> getGauges(String agentRollupId, long from, long to, CassandraProfile profile);

    // from is INCLUSIVE
    CompletionStage<List<GaugeValue>> readGaugeValues(String agentRollupId, String gaugeName, long from, long to,
            int rollupLevel, CassandraProfile profile);

    CompletionStage<Long> getOldestCaptureTime(String agentRollupId, String gaugeName, int rollupLevel, CassandraProfile profile);

    @Value.Immutable
    @Styles.AllParameters
    public interface Gauge {
        String name();
        String display();
        List<String> displayParts();
        boolean counter();
        String unit();
        String grouping();
    }
}
