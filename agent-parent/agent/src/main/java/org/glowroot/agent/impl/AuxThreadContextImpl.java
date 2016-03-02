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
package org.glowroot.agent.impl;

import org.glowroot.agent.model.ThreadContextImpl;
import org.glowroot.agent.model.TraceEntryImpl;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTraceEntry;
import org.glowroot.agent.plugin.api.util.FastThreadLocal.Holder;

public class AuxThreadContextImpl implements AuxThreadContext {

    private final ThreadContextImpl parentThreadContext;
    private final TraceEntryImpl parentTraceEntry;
    private final TraceEntryImpl parentThreadContextTailEntry;
    private final TransactionRegistry transactionRegistry;
    private final TransactionServiceImpl transactionService;

    public AuxThreadContextImpl(ThreadContextImpl parentThreadContext,
            TraceEntryImpl parentTraceEntry, TraceEntryImpl parentThreadContextTailEntry,
            TransactionRegistry transactionRegistry, TransactionServiceImpl transactionService) {
        this.parentThreadContext = parentThreadContext;
        this.parentTraceEntry = parentTraceEntry;
        this.parentThreadContextTailEntry = parentThreadContextTailEntry;
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;
    }

    @Override
    public TraceEntry start() {
        Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder =
                transactionRegistry.getCurrentThreadContextHolder();
        if (threadContextHolder.get() != null) {
            return NopTraceEntry.INSTANCE;
        }
        return transactionService.startAuxThreadContextInternal(parentThreadContext,
                parentTraceEntry, parentThreadContextTailEntry, threadContextHolder);
    }
}
