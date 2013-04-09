/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.weaving;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import io.informant.markers.NotThreadSafe;
import io.informant.markers.PartiallyThreadSafe;
import io.informant.markers.UsedByGeneratedBytecode;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@UsedByGeneratedBytecode
@PartiallyThreadSafe("AdviceFlowHolder returned by getInnerHolder() is not thread safe")
public class AdviceFlowOuterHolder {

    // runtime cache of AdviceFlowThreadLocal for Advice with Pointcut.captureNested() "false".
    // a given AdviceFlowThreadLocal is shared across all of a given advice's pointcuts.
    // the call to getSharedInstance() is cached in a woven class's static initializer in order to
    // minimize the semi-expensive cache lookup
    private static final LoadingCache<Class<?>, AdviceFlowOuterHolder> sharedThreadLocals =
            CacheBuilder.newBuilder().build(new CacheLoader<Class<?>, AdviceFlowOuterHolder>() {
                @Override
                public AdviceFlowOuterHolder load(Class<?> key) {
                    return new AdviceFlowOuterHolder();
                }
            });

    // it is faster to use a mutable holder object and always perform ThreadLocal.get() and never
    // use ThreadLocal.set()
    private final ThreadLocal<AdviceFlowHolder> topHolder = new ThreadLocal<AdviceFlowHolder>() {
        @Override
        protected AdviceFlowHolder initialValue() {
            return new AdviceFlowHolder(true);
        }
    };

    @UsedByGeneratedBytecode
    public static AdviceFlowOuterHolder getSharedInstance(Class<?> adviceClass) {
        return sharedThreadLocals.getUnchecked(adviceClass);
    }

    private AdviceFlowOuterHolder() {}

    @UsedByGeneratedBytecode
    public AdviceFlowHolder getInnerHolder() {
        return topHolder.get();
    }

    @UsedByGeneratedBytecode
    @NotThreadSafe
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
