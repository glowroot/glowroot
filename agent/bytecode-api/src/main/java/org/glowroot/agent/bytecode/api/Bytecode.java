/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.bytecode.api;

import java.lang.reflect.Method;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.TraceEntry;

public class Bytecode {

    private static final BytecodeService service = BytecodeServiceHolder.get();

    private Bytecode() {}

    public static void enteringMainMethod() {
        service.enteringMain();
    }

    public static void exitingGetPlatformMBeanServer() {
        service.exitingGetPlatformMBeanServer();
    }

    public static ThreadContextThreadLocal.Holder getCurrentThreadContextHolder() {
        return service.getCurrentThreadContextHolder();
    }

    public static ThreadContextPlus createOptionalThreadContext(
            ThreadContextThreadLocal.Holder threadContextHolder) {
        return service.createOptionalThreadContext(threadContextHolder);
    }

    public static Object getClassMeta(int index) throws Exception {
        return service.getClassMeta(index);
    }

    public static Object getMethodMeta(int index) throws Exception {
        return service.getMethodMeta(index);
    }

    public static MessageTemplate createMessageTemplate(String template, Method method) {
        return service.createMessageTemplate(template, method);
    }

    public static MessageSupplier createMessageSupplier(MessageTemplate template,
            Object receiver, String methodName, @Nullable Object... args) {
        return service.createMessageSupplier(template, receiver, methodName, args);
    }

    public static String getMessageText(MessageTemplate template, Object receiver,
            String methodName, @Nullable Object... args) {
        return service.getMessageText(template, receiver, methodName, args);
    }

    public static void updateWithReturnValue(TraceEntry traceEntry, @Nullable Object returnValue) {
        service.updateWithReturnValue(traceEntry, returnValue);
    }
}
