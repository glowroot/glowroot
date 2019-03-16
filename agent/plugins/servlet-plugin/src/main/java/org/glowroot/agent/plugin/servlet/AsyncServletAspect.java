/*
 * Copyright 2016-2019 the original author or authors.
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

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletRequest;

import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class AsyncServletAspect {

    static final String GLOWROOT_AUX_CONTEXT_REQUEST_ATTRIBUTE = "glowroot$auxContext";

    @Pointcut(className = "javax.servlet.ServletRequest", methodName = "startAsync",
            methodParameterTypes = {".."})
    public static class StartAsyncAdvice {
        @OnReturn
        public static void onReturn(@BindReturn AsyncContext asyncContext,
                final ThreadContext context) {
            context.setTransactionAsync();
            asyncContext.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    context.setTransactionAsyncComplete();
                }
                @Override
                public void onTimeout(AsyncEvent event) {
                    Throwable throwable = event.getThrowable();
                    if (throwable != null) {
                        context.setTransactionError(throwable);
                    }
                    context.setTransactionAsyncComplete();
                }
                @Override
                public void onError(AsyncEvent event) {
                    Throwable throwable = event.getThrowable();
                    if (throwable != null) {
                        context.setTransactionError(throwable);
                    }
                    context.setTransactionAsyncComplete();
                }
                @Override
                public void onStartAsync(AsyncEvent event) {
                    AsyncContext asyncContext = event.getAsyncContext();
                    if (asyncContext != null) {
                        asyncContext.addListener(this);
                    }
                }
            });
        }
    }

    // IMPORTANT complete is not called if client disconnects, but it's still useful to capture for
    // normal requests since it is called from an auxiliary thread, and by calling
    // setTransactinAsyncComplete() first from the auxiliary thread, the transaction will wait to
    // complete until the auxiliary thread completes, and won't leave behind "this auxiliary thread
    // was still running when the transaction ended" trace entry
    @Pointcut(className = "javax.servlet.AsyncContext", methodName = "complete",
            methodParameterTypes = {})
    public static class CompleteAdvice {
        // using @OnBefore instead of @OnReturn since it is during complete() that AsyncEvent is
        // fired, and want the setTransactionAsyncComplete() in this (auxiliary) thread to win
        @OnBefore
        public static void onBefore(ThreadContext context) {
            context.setTransactionAsyncComplete();
        }
    }

    @Pointcut(className = "javax.servlet.AsyncContext", methodName = "dispatch",
            methodParameterTypes = {".."}, nestingGroup = "servlet-dispatch")
    public static class DispatchAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindReceiver AsyncContext asyncContext) {
            ServletRequest request = asyncContext.getRequest();
            if (request == null) {
                return;
            }
            request.setAttribute(GLOWROOT_AUX_CONTEXT_REQUEST_ATTRIBUTE,
                    context.createAuxThreadContext());
        }
    }
}
