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

import javax.annotation.Nullable;

import org.glowroot.agent.bytecode.api.BytecodeService;
import org.glowroot.agent.bytecode.api.MessageTemplate;
import org.glowroot.agent.bytecode.api.ThreadContextPlus;
import org.glowroot.agent.bytecode.api.ThreadContextThreadLocal;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.weaving.BootstrapMetaHolders;
import org.glowroot.agent.weaving.GenericMessageSupplier;
import org.glowroot.agent.weaving.MessageTemplateImpl;

public class WeavingServiceImpl implements BytecodeService {

    private final TransactionRegistry transactionRegistry;
    private final TransactionService transactionService;

    public WeavingServiceImpl(TransactionRegistry transactionRegistry,
            TransactionService transactionService) {
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;
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
}
