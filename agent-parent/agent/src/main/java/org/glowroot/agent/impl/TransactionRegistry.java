/*
 * Copyright 2011-2015 the original author or authors.
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

import org.glowroot.agent.model.Transaction;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;

public class TransactionRegistry {

    // collection of active running transactions
    private final Collection<Transaction> transactions = Sets.newConcurrentHashSet();

    // active running transaction being executed by the current thread
    private final FastThreadLocal</*@Nullable*/ Transaction> currentTransaction =
            new FastThreadLocal</*@Nullable*/ Transaction>();

    @Nullable
    Transaction getCurrentTransaction() {
        return currentTransaction.get();
    }

    void addTransaction(Transaction transaction) {
        currentTransaction.set(transaction);
        transactions.add(transaction);
    }

    void removeTransaction(Transaction transaction) {
        currentTransaction.set(null);
        transactions.remove(transaction);
    }

    public Collection<Transaction> getTransactions() {
        return transactions;
    }
}
