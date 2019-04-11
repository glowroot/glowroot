/*
 * Copyright 2018-2019 the original author or authors.
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

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.bytecode.api.BytecodeService;
import org.glowroot.agent.bytecode.api.MessageTemplate;
import org.glowroot.agent.bytecode.api.ThreadContextPlus;
import org.glowroot.agent.bytecode.api.ThreadContextThreadLocal;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.MethodInfo;
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

    private final PreloadSomeSuperTypesCache preloadSomeSuperTypesCache;

    private final ThreadLocal</*@Nullable*/ Set<String>> currentlyLoadingTypesHolder =
            new ThreadLocal</*@Nullable*/ Set<String>>();

    public BytecodeServiceImpl(TransactionRegistry transactionRegistry,
            TransactionService transactionService,
            PreloadSomeSuperTypesCache preloadSomeSuperTypesCache) {
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;

        this.preloadSomeSuperTypesCache = preloadSomeSuperTypesCache;
    }

    public void setOnEnteringMain(OnEnteringMain onEnteringMain) {
        this.onEnteringMain = onEnteringMain;
    }

    public void setOnExitingGetPlatformMBeanServer(Runnable onExitingGetPlatformMBeanServer) {
        this.onExitingGetPlatformMBeanServer = onExitingGetPlatformMBeanServer;
    }

    @Override
    public void enteringMainMethod(String mainClass, @Nullable String /*@Nullable*/ [] mainArgs) {
        enteringMainMethod(mainClass, mainArgs, mainClass, "main");
    }

    @Override
    public void enteringApacheCommonsDaemonLoadMethod(String mainClass,
            @Nullable String /*@Nullable*/ [] mainArgs) {
        enteringMainMethod(mainClass, mainArgs, "org.apache.commons.daemon.support.DaemonLoader",
                "load");
    }

    @Override
    public void enteringPossibleProcrunStartMethod(String className, String methodName,
            @Nullable String /*@Nullable*/ [] methodArgs) {
        enteringMainMethod(className, methodArgs, className, methodName);
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
    public MessageTemplate createMessageTemplate(String template, MethodInfo methodInfo) {
        return MessageTemplateImpl.create(template, methodInfo);
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

    @Override
    public void logThrowable(Throwable throwable) {
        logger.error(throwable.getMessage(), throwable);
    }

    @Override
    public void preloadSomeSuperTypes(ClassLoader loader, @Nullable String className) {
        if (className == null) {
            return;
        }
        Set<String> preloadSomeSuperTypes = preloadSomeSuperTypesCache.get(className);
        if (preloadSomeSuperTypes.isEmpty()) {
            return;
        }
        Set<String> currentlyLoadingTypes = currentlyLoadingTypesHolder.get();
        boolean topLevel;
        if (currentlyLoadingTypes == null) {
            // using linked hash set so that top level class can be found as first element
            currentlyLoadingTypes = Sets.newLinkedHashSet();
            currentlyLoadingTypesHolder.set(currentlyLoadingTypes);
            topLevel = true;
        } else if (currentlyLoadingTypes.iterator().next().equals(className)) {
            // not top level, and need to abort the (nested) defineClass() that this is inside of,
            // otherwise the defineClass() that this is inside of will succeed, but then the top
            // level defineClass() will fail with "attempted duplicate class definition for name"
            throw new AbortPreload();
        } else {
            topLevel = false;
        }
        if (!currentlyLoadingTypes.add(className)) {
            // already loading className, prevent infinite loop / StackOverflowError, see
            // AnalyzedClassPlanBeeWithBadPreloadCacheIT
            return;
        }
        try {
            for (String superClassName : preloadSomeSuperTypes) {
                logger.debug("pre-loading super class {} for {}, in loader={}@{}", superClassName,
                        className, loader.getClass().getName(), loader.hashCode());
                try {
                    Class.forName(superClassName, false, loader);
                } catch (ClassNotFoundException e) {
                    logger.debug("super class {} not found (for sub-class {})", superClassName,
                            className, e);
                } catch (LinkageError e) {
                    // this can happen, e.g. ClassCircularityError, under strange circumstances,
                    // see AnalyzedClassPlanBeeWithMoreBadPreloadCacheIT
                    logger.debug("linkage error occurred while loading super class {} (for"
                            + " sub-class {})", superClassName, className, e);
                } catch (AbortPreload e) {
                }
            }
        } finally {
            if (topLevel) {
                currentlyLoadingTypesHolder.remove();
            } else {
                currentlyLoadingTypes.remove(className);
            }
        }
    }

    public void enteringMainMethod(String className, @Nullable String /*@Nullable*/ [] methodArgs,
            String expectedTopLevelClassName, String expectedTopLevelMethodName) {
        if (onEnteringMain == null) {
            if (DEBUG_MAIN_CLASS) {
                logger.info("entering {}.main(), but callback not set yet", className,
                        new Exception("location stack trace"));
            }
            return;
        }
        if (hasRunOnEnteringMain.get()) {
            // no need to spend effort checking anything else
            return;
        }
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        if (ignoreMainClass(expectedTopLevelClassName, expectedTopLevelMethodName, stackTrace)) {
            if (DEBUG_MAIN_CLASS) {
                logger.info("ignoring {}.main()", className, new Exception("location stack trace"));
            }
            return;
        }
        if (className.equals("com.ibm.java.diagnostics.healthcenter.agent.mbean.HCLaunchMBean")) {
            // IBM J9 VM -Xhealthcenter
            return;
        }
        if (hasRunOnEnteringMain.getAndSet(true)) {
            // unexpected and strange race condition on valid main methods
            return;
        }
        String unwrappedMainClass;
        if (className.startsWith("org.tanukisoftware.wrapper.")
                && methodArgs != null && methodArgs.length > 0) {
            unwrappedMainClass = methodArgs[0];
        } else {
            unwrappedMainClass = className;
        }
        if (DEBUG_MAIN_CLASS) {
            logger.info("entering {}.main()", className, new Exception("location stack trace"));
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

    @SuppressWarnings("serial")
    private static class AbortPreload extends RuntimeException {}
}
