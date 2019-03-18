/*
 * Copyright 2019 the original author or authors.
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
package org.glowroot.agent.plugin.netty._;

import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;

public class Util {

    public static TraceEntry startAsyncTransaction(OptionalThreadContext context,
            @Nullable String methodName, @Nullable String uri, TimerName timerName) {
        String path = getPath(uri);
        String message;
        if (methodName == null) {
            message = uri;
        } else {
            message = methodName + " " + uri;
        }
        TraceEntry traceEntry =
                context.startTransaction("Web", path, MessageSupplier.create(message), timerName);
        context.setTransactionAsync();
        return traceEntry;
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
