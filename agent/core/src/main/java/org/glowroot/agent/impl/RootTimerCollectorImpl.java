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
package org.glowroot.agent.impl;

import java.util.List;

import com.google.common.collect.Lists;

import org.glowroot.agent.impl.Transaction.RootTimerCollector;
import org.glowroot.agent.model.CommonTimerImpl;
import org.glowroot.agent.model.MutableTraceTimer;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class RootTimerCollectorImpl implements RootTimerCollector {

    private final List<MutableTraceTimer> rootMutableTimers = Lists.newArrayList();

    @Override
    public void mergeRootTimer(CommonTimerImpl rootTimer) {
        mergeRootTimer(rootTimer, rootMutableTimers);
    }

    List<Trace.Timer> toProto() {
        List<Trace.Timer> rootTimers = Lists.newArrayList();
        for (MutableTraceTimer rootMutableTimer : rootMutableTimers) {
            rootTimers.add(rootMutableTimer.toProto());
        }
        return rootTimers;
    }

    List<MutableTraceTimer> getRootTimers() {
        return rootMutableTimers;
    }

    private static void mergeRootTimer(CommonTimerImpl toBeMergedRootTimer,
            List<MutableTraceTimer> rootTimers) {
        for (MutableTraceTimer rootTimer : rootTimers) {
            if (toBeMergedRootTimer.getName().equals(rootTimer.getName())
                    && toBeMergedRootTimer.isExtended() == rootTimer.isExtended()) {
                rootTimer.merge(toBeMergedRootTimer);
                return;
            }
        }
        MutableTraceTimer rootTimer = MutableTraceTimer.createRootTimer(
                toBeMergedRootTimer.getName(), toBeMergedRootTimer.isExtended());
        rootTimer.merge(toBeMergedRootTimer);
        rootTimers.add(rootTimer);
    }
}
