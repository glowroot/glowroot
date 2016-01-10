/*
 * Copyright 2011-2016 the original author or authors.
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

import java.util.Collection;

import javax.annotation.Nullable;

import com.google.common.collect.Sets;

import org.glowroot.agent.model.ThreadContextImpl;
import org.glowroot.agent.model.Transaction;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;

public class TransactionRegistry {

    // collection of active running transactions
    private final Collection<Transaction> transactions = Sets.newConcurrentHashSet();

    // active thread context being executed by the current thread
    private final FastThreadLocal</*@Nullable*/ ThreadContextImpl> currentThreadContext =
            new FastThreadLocal</*@Nullable*/ ThreadContextImpl>();

    @Nullable
    Transaction getCurrentTransaction() {
        ThreadContextImpl threadContext = currentThreadContext.get();
        if (threadContext == null) {
            return null;
        }
        return threadContext.getTransaction();
    }

    @Nullable
    ThreadContextImpl getCurrentThreadContext() {
        return currentThreadContext.get();
    }

    void addTransaction(Transaction transaction) {
        currentThreadContext.set(transaction.getMainThreadContext());
        transactions.add(transaction);
    }

    void removeTransaction(Transaction transaction) {
        currentThreadContext.set(null);
        transactions.remove(transaction);
    }

    public void setAuxThreadContext(ThreadContextImpl auxThreadContext) {
        currentThreadContext.set(auxThreadContext);
    }

    public void removeAuxThreadContext() {
        currentThreadContext.set(null);
    }

    public Collection<Transaction> getTransactions() {
        return transactions;
    }
}
