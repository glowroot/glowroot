/*
 * Copyright 2012-2016 the original author or authors.
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
package org.glowroot.agent.weaving;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.glowroot.common.util.UsedByGeneratedBytecode;

@UsedByGeneratedBytecode
public class AdviceFlowOuterHolder {

    private static final ConcurrentMap<String, AdviceFlowOuterHolder> cache =
            new ConcurrentHashMap<String, AdviceFlowOuterHolder>();

    // it is faster to use a mutable holder object and always perform ThreadLocal.get() and never
    // use ThreadLocal.set(), because the value is more likely to be found in the ThreadLocalMap
    // direct hash slot and avoid the slow path ThreadLocalMap.getEntryAfterMiss()
    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<AdviceFlowHolder> topHolder = new ThreadLocal<AdviceFlowHolder>() {
        @Override
        protected AdviceFlowHolder initialValue() {
            return new AdviceFlowHolder(true);
        }
    };

    @UsedByGeneratedBytecode
    public static AdviceFlowOuterHolder create() {
        return new AdviceFlowOuterHolder();
    }

    @UsedByGeneratedBytecode
    public static AdviceFlowOuterHolder get(String timerName) {
        AdviceFlowOuterHolder outerHolder = cache.get(timerName);
        if (outerHolder == null) {
            outerHolder = new AdviceFlowOuterHolder();
            AdviceFlowOuterHolder priorOuterHolder = cache.putIfAbsent(timerName, outerHolder);
            if (priorOuterHolder != null) {
                outerHolder = priorOuterHolder;
            }
        }
        return outerHolder;
    }

    private AdviceFlowOuterHolder() {}

    @UsedByGeneratedBytecode
    public AdviceFlowHolder getInnerHolder() {
        return topHolder.get();
    }

    @UsedByGeneratedBytecode
    public static class AdviceFlowHolder {
        private boolean top;
        private AdviceFlowHolder(boolean top) {
            this.top = top;
        }
        @UsedByGeneratedBytecode
        public boolean isTop() {
            return top;
        }
        @UsedByGeneratedBytecode
        public void setTop(boolean top) {
            this.top = top;
        }
    }
}
