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
package org.glowroot.agent.impl;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.bytecode.api.BytecodeService;
import org.glowroot.agent.bytecode.api.MessageTemplate;
import org.glowroot.agent.bytecode.api.ThreadContextPlus;
import org.glowroot.agent.bytecode.api.ThreadContextThreadLocal;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.weaving.BootstrapMetaHolders;
import org.glowroot.agent.weaving.GenericMessageSupplier;
import org.glowroot.agent.weaving.MessageTemplateImpl;

public class BytecodeServiceImpl implements BytecodeService {

    private static final boolean DEBUG_MAIN_CLASS = Boolean.getBoolean("glowroot.debug.mainClass");

    private static final Logger logger = LoggerFactory.getLogger(BytecodeServiceImpl.class);

    private final TransactionRegistry transactionRegistry;
    private final TransactionService transactionService;

    private volatile @MonotonicNonNull OnEnteringMain onEnteringMain;
    private final AtomicBoolean hasRunOnEnteringMain = new AtomicBoolean();

    private volatile @MonotonicNonNull Runnable onExitingGetPlatformMBeanServer;
    private final AtomicBoolean hasRunOnExitingGetPlatformMBeanServer = new AtomicBoolean();

    public BytecodeServiceImpl(TransactionRegistry transactionRegistry,
            TransactionService transactionService) {
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;
    }

    public void setOnEnteringMain(OnEnteringMain onEnteringMain) {
        this.onEnteringMain = onEnteringMain;
    }

    public void setOnExitingGetPlatformMBeanServer(Runnable onExitingGetPlatformMBeanServer) {
        this.onExitingGetPlatformMBeanServer = onExitingGetPlatformMBeanServer;
    }

    @Override
    public void enteringMain(String mainClass, @Nullable String /*@Nullable*/ [] mainArgs) {
        enteringMainCommon(mainClass, mainArgs, mainClass, "main");
    }

    @Override
    public void enteringApacheCommonsDaemonLoad(String mainClass,
            @Nullable String /*@Nullable*/ [] mainArgs) {
        enteringMainCommon(mainClass, mainArgs, "org.apache.commons.daemon.support.DaemonLoader",
                "load");
    }

    @Override
    public void exitingGetPlatformMBeanServer() {
        if (onExitingGetPlatformMBeanServer == null) {
            return;
        }
        if (hasRunOnExitingGetPlatformMBeanServer.getAndSet(true)) {
            return;
        }
        try {
            onExitingGetPlatformMBeanServer.run();
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    public ThreadContextThreadLocal.Holder getCurrentThreadContextHolder() {
        return transactionRegistry.getCurrentThreadContextHolder();
    }

    @Override
    public ThreadContextPlus createOptionalThreadContext(
            ThreadContextThreadLocal.Holder threadContextHolder, int currentNestingGroupId,
            int currentSuppressionKeyId) {
        return OptionalThreadContextImpl.create(transactionService, threadContextHolder,
                currentNestingGroupId, currentSuppressionKeyId);
    }

    @Override
    public Object getClassMeta(int index) throws Exception {
        return BootstrapMetaHolders.getClassMeta(index);
    }

    @Override
    public Object getMethodMeta(int index) throws Exception {
        return BootstrapMetaHolders.getMethodMeta(index);
    }

    @Override
    public MessageTemplate createMessageTemplate(String template, Method method) {
        return MessageTemplateImpl.create(template, method);
    }

    @Override
    public MessageSupplier createMessageSupplier(MessageTemplate template, Object receiver,
            String methodName, @Nullable Object... args) {
        return GenericMessageSupplier.create((MessageTemplateImpl) template, receiver, methodName,
                args);
    }

    @Override
    public String getMessageText(MessageTemplate template, Object receiver, String methodName,
            @Nullable Object... args) {
        return GenericMessageSupplier
                .create((MessageTemplateImpl) template, receiver, methodName, args)
                .getMessageText();
    }

    @Override
    public void updateWithReturnValue(TraceEntry traceEntry, @Nullable Object returnValue) {
        GenericMessageSupplier.updateWithReturnValue(traceEntry, returnValue);
    }

    public void enteringMainCommon(String mainClass, @Nullable String /*@Nullable*/ [] mainArgs,
            String expectedTopLevelClass, String expectedTopLevelMethodName) {
        if (onEnteringMain == null) {
            if (DEBUG_MAIN_CLASS) {
                logger.info("callback not set yet: {}", mainClass,
                        new Exception("location stack trace"));
            }
            return;
        }
        if (hasRunOnEnteringMain.get()) {
            // no need to spend effort checking anything else
            return;
        }
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        if (ignoreMainClass(expectedTopLevelClass, expectedTopLevelMethodName, stackTrace)) {
            if (DEBUG_MAIN_CLASS) {
                logger.info("ignoring main class: {}", mainClass,
                        new Exception("location stack trace"));
            }
            return;
        }
        if (mainClass.equals("com.ibm.java.diagnostics.healthcenter.agent.mbean.HCLaunchMBean")) {
            // IBM JVM -Xhealthcenter
            return;
        }
        if (hasRunOnEnteringMain.getAndSet(true)) {
            // unexpected and strange race condition on valid main methods
            return;
        }
        if (DEBUG_MAIN_CLASS) {
            logger.info("main class: {}", mainClass);
        }
        String unwrappedMainClass;
        if (mainClass.startsWith("org.tanukisoftware.wrapper.")
                && mainArgs != null && mainArgs.length > 0) {
            unwrappedMainClass = mainArgs[0];
        } else {
            unwrappedMainClass = mainClass;
        }
        try {
            onEnteringMain.run(unwrappedMainClass);
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    private static boolean ignoreMainClass(String expectedTopLevelClass,
            String expectedTopLevelMethodName, StackTraceElement[] stackTrace) {
        if (stackTrace.length == 0) {
            return true;
        }
        StackTraceElement topStackTraceElement = stackTrace[stackTrace.length - 1];
        return !topStackTraceElement.getClassName().equals(expectedTopLevelClass)
                || !expectedTopLevelMethodName.equals(topStackTraceElement.getMethodName());
    }

    public interface OnEnteringMain {
        void run(@Nullable String mainClass) throws Exception;
    }
}
