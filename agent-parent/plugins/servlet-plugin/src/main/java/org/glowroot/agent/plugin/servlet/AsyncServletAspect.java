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
package org.glowroot.agent.plugin.servlet;

import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class AsyncServletAspect {

    @Pointcut(className = "javax.servlet.ServletRequest", methodName = "startAsync",
            methodParameterTypes = {}, nestingGroup = "servlet-start-async")
    public static class StartAsyncAdvice {
        @OnReturn
        public static void onReturn(ThreadContext context) {
            context.setTransactionAsync();
        }
    }

    @Pointcut(className = "javax.servlet.ServletRequest", methodName = "startAsync",
            methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse"},
            nestingGroup = "servlet-start-async")
    public static class StartAsyncTwoArgAdvice {
        @OnReturn
        public static void onReturn(ThreadContext context) {
            context.setTransactionAsync();
        }
    }

    @Pointcut(className = "javax.servlet.AsyncContext", methodName = "complete",
            methodParameterTypes = {})
    public static class CompleteAdvice {
        @OnReturn
        public static void onReturn(ThreadContext context) {
            context.completeAsyncTransaction();
        }
    }
}
