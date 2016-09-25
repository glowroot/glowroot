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
package org.glowroot.agent.plugin.spray;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class SprayAspect {

    @Shim("spray.can.parsing.Result$Emit")
    public interface Emit {
        @Shim("spray.http.HttpMessagePart part()")
        @Nullable
        Object part();
    }

    @Shim("spray.http.HttpMessageStart")
    public interface HttpMessageStart {
        @Shim("spray.http.HttpMessage message()")
        @Nullable
        Object message();
    }

    @Shim("spray.http.HttpRequest")
    public interface HttpRequest {
        @Shim("spray.http.HttpMethod method()")
        @Nullable
        Object method();

        @Shim("spray.http.Uri uri()")
        @Nullable
        Object uri();
    }

    @Pointcut(className = "spray.can.server.RequestParsing$$anon$1$$anon$2",
            methodName = "spray$can$server$RequestParsing$$anon$$anon$$handleParsingResult",
            methodParameterTypes = {"spray.can.parsing.Result"}, timerName = "http request")
    public static class Advice4 {

        private static final TimerName timerName = Agent.getTimerName(Advice4.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter @Nullable Emit emit) {
            if (emit == null) {
                return null;
            }
            Object part = emit.part();
            if (!(part instanceof HttpMessageStart)) {
                return null;
            }
            Object messageObj = ((HttpMessageStart) part).message();
            if (!(messageObj instanceof HttpRequest)) {
                return null;
            }
            HttpRequest request = (HttpRequest) messageObj;
            Object uriObj = request.uri();
            String uri;
            if (uriObj == null) {
                uri = "";
            } else {
                uri = uriObj.toString();
            }
            String path = getPath(uri);
            Object methodObj = request.method();
            String message;
            if (methodObj == null) {
                message = uri;
            } else {
                message = methodObj.toString() + " " + uri;
            }
            TraceEntry traceEntry = context.startTransaction("Web", path,
                    MessageSupplier.create(message), timerName);
            context.setAsyncTransaction();
            return traceEntry;
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(throwable);
            }
        }

        private static String getPath(@Nullable String uri) {
            String path;
            if (uri == null) {
                path = "";
            } else {
                int index = uri.indexOf('?');
                if (index == -1) {
                    path = uri;
                } else {
                    path = uri.substring(0, index);
                }
            }
            return path;
        }
    }

    @Pointcut(className = "spray.*", methodName = "renderResponsePartRenderingContext",
            methodParameterTypes = {".."})
    public static class RenderResponseAdvice {
        @OnAfter
        public static void onAfter(ThreadContext context) {
            context.completeAsyncTransaction();
        }
    }
}
