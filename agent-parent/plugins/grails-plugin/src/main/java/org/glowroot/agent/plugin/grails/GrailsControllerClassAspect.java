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
package org.glowroot.agent.plugin.grails;

import org.glowroot.agent.plugin.api.*;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.weaving.*;

public class GrailsControllerClassAspect {

    @Shim("grails.core.GrailsControllerClass")
    public interface GrailsControllerClass {
        String getDefaultAction();

        String getName();
    }

    @Pointcut(className = "grails.core.GrailsControllerClass",
            methodName = "invoke",
            methodParameterTypes = {"java.lang.Object", "java.lang.String"}, timerName = "grails controller")
    public static class ControllerAdvice {
        private static final TimerName timerName = Agent.getTimerName(ControllerAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver GrailsControllerClass grailsController,
                                          @BindParameter Object controller, @BindParameter String action) {
            String controllerName = grailsController.getName();
            String actionName = action == null ? grailsController.getDefaultAction() : action;
            context.setTransactionName(controllerName + "/" + actionName, Priority.CORE_PLUGIN);

            return context.startTraceEntry(MessageSupplier.from("grails controller: {}.{}()",
                    controllerName,
                    actionName), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable, @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(throwable);
        }
    }
}
