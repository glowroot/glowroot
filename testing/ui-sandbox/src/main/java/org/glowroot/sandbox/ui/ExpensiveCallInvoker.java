/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.sandbox.ui;

import java.lang.reflect.Method;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExpensiveCallInvoker {

    private static final Logger logger = LoggerFactory.getLogger(ExpensiveCallInvoker.class);

    private final Method getSpanMessageMethod;

    public ExpensiveCallInvoker(Class<?> clazz) {
        Class<?> expensiveCallClass = null;
        try {
            expensiveCallClass = Class.forName("org.glowroot.sandbox.ui.ExpensiveCall", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        getSpanMessageMethod = getMethod(expensiveCallClass, "getSpanMessage");
    }

    public String getSpanMessage(Object expensiveCall) {
        if (getSpanMessageMethod == null) {
            return null;
        }
        try {
            return (String) getSpanMessageMethod.invoke(expensiveCall);
        } catch (Throwable t) {
            logger.warn("error calling ExpensiveCall.getSpanMessage()", t);
            return "<error calling ExpensiveCall.getSpanMessage()>";
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
