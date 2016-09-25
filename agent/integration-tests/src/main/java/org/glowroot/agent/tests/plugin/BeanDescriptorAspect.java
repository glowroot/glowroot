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
package org.glowroot.agent.tests.plugin;

import java.lang.reflect.Method;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindMethodMeta;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

// this is for testing weaving of bootstrap classes
public class BeanDescriptorAspect {

    @Pointcut(className = "java.beans.BeanDescriptor", methodName = "getBeanClass",
            methodParameterTypes = {}, timerName = "get bean class")
    public static class GetBeanClassAdvice {

        private static final TimerName timerName = Agent.getTimerName(GetBeanClassAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context,
                @BindClassMeta TestClassMeta meta) {
            return context.startTraceEntry(MessageSupplier.create(meta.clazz.getName()), timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "java.beans.BeanDescriptor", methodName = "getCustomizerClass",
            methodParameterTypes = {}, timerName = "get customizer class")
    public static class GetCustomizerClassAdvice {

        private static final TimerName timerName =
                Agent.getTimerName(GetCustomizerClassAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context,
                @BindMethodMeta TestMethodMeta meta) {
            return context.startTraceEntry(MessageSupplier.create(meta.method.getName()),
                    timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    public static class TestClassMeta {

        private final Class<?> clazz;

        public TestClassMeta(Class<?> clazz) {
            this.clazz = clazz;
        }
    }

    public static class TestMethodMeta {

        private final Method method;

        public TestMethodMeta(Method method) {
            this.method = method;
        }
    }
}
