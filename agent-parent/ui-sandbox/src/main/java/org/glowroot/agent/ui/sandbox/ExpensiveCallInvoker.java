/*
 * Copyright 2014-2016 the original author or authors.
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

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;

public class ExpensiveCallInvoker {

    private static final Logger logger = Agent.getLogger(ExpensiveCallInvoker.class);

    private final Method getTraceEntryMessageMethod;

    public ExpensiveCallInvoker(Class<?> clazz) {
        Class<?> expensiveCallClass = null;
        try {
            expensiveCallClass = Class.forName("org.glowroot.agent.ui.sandbox.ExpensiveCall", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        getTraceEntryMessageMethod = getMethod(expensiveCallClass, "getTraceEntryMessage");
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

    private static Method getMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        if (clazz == null) {
            return null;
        }
        try {
            return clazz.getMethod(methodName, parameterTypes);
        } catch (SecurityException e) {
            logger.warn(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.warn(e.getMessage(), e);
            return null;
        }
    }
}
