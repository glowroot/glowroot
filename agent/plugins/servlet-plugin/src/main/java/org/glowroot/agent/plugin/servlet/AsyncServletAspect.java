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

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class AsyncServletAspect {

    static final String GLOWROOT_AUX_CONTEXT_REQUEST_ATTRIBUTE = "glowroot$auxContext";

    @Shim("javax.servlet.AsyncContext")
    public interface AsyncContext {

        @Shim("javax.servlet.ServletRequest getRequest()")
        @Nullable
        ServletRequest glowroot$getRequest();
    }

    @Shim("javax.servlet.ServletRequest")
    public interface ServletRequest {

        void setAttribute(String name, Object o);
    }

    @Pointcut(className = "javax.servlet.ServletRequest", methodName = "startAsync",
            methodParameterTypes = {".."})
    public static class StartAsyncAdvice {
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
            context.setTransactionAsyncComplete();
        }
    }

    @Pointcut(className = "javax.servlet.AsyncContext", methodName = "dispatch",
            methodParameterTypes = {".."}, nestingGroup = "servlet-dispatch")
    public static class DispatchAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindReceiver AsyncContext asyncContext) {
            ServletRequest request = asyncContext.glowroot$getRequest();
            if (request == null) {
                return;
            }
            request.setAttribute(GLOWROOT_AUX_CONTEXT_REQUEST_ATTRIBUTE,
                    context.createAuxThreadContext());
        }
    }
}
