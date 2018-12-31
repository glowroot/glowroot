/*
 * Copyright 2014-2018 the original author or authors.
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
package org.glowroot.agent.ui.sandbox;

import java.lang.reflect.Method;

import org.glowroot.agent.plugin.api.ClassInfo;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.util.Reflection;

public class ExpensiveCallInvoker {

    private static final Logger logger = Logger.getLogger(ExpensiveCallInvoker.class);

    private final Method getTraceEntryMessageMethod;

    public ExpensiveCallInvoker(ClassInfo classInfo) {
        Class<?> expensiveCallClass = Reflection.getClassWithWarnIfNotFound(
                "org.glowroot.agent.ui.sandbox.ExpensiveCall", classInfo.getLoader());
        getTraceEntryMessageMethod =
                Reflection.getMethod(expensiveCallClass, "getTraceEntryMessage");
    }

    String getTraceEntryMessage(Object expensiveCall) {
        if (getTraceEntryMessageMethod == null) {
            return null;
        }
        try {
            return (String) getTraceEntryMessageMethod.invoke(expensiveCall);
        } catch (Throwable t) {
            logger.warn("error calling ExpensiveCall.getTraceEntryMessage()", t);
            return "<error calling ExpensiveCall.getTraceEntryMessage()>";
        }
    }
}
