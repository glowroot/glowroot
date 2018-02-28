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

import javax.annotation.Nullable;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
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

    private static final Logger logger = LoggerFactory.getLogger(BytecodeServiceImpl.class);

    private final TransactionRegistry transactionRegistry;
    private final TransactionService transactionService;

    private volatile @MonotonicNonNull OnEnteringMain onEnteringMain;
    private final AtomicBoolean hasRunOnEnteringMain = new AtomicBoolean();

    public BytecodeServiceImpl(TransactionRegistry transactionRegistry,
            TransactionService transactionService) {
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;
    }

    public void setOnEnteringMain(OnEnteringMain onEnteringMain) {
        this.onEnteringMain = onEnteringMain;
    }

    @Override
    public void enteringMain() {
        if (onEnteringMain == null) {
            return;
        }
        if (hasRunOnEnteringMain.getAndSet(true)) {
            return;
        }
        try {
            onEnteringMain.run();
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
            ThreadContextThreadLocal.Holder threadContextHolder) {
        return OptionalThreadContextImpl.create(transactionService, threadContextHolder);
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

    public interface OnEnteringMain {
        void run() throws Exception;
    }
}
