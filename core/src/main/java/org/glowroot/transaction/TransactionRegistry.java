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
package org.glowroot.transaction;

import java.util.Collection;

import javax.annotation.Nullable;

import com.google.common.collect.Sets;

import org.glowroot.transaction.model.Transaction;

public class TransactionRegistry {

    // collection of active running transactions
    private final Collection<Transaction> transactions = Sets.newConcurrentHashSet();

    // active running transaction being executed by the current thread
    //
    // it is faster to use a mutable holder object and always perform ThreadLocal.get() and never
    // use ThreadLocal.set(), because the value is more likely to be found in the ThreadLocalMap
    // direct hash slot and avoid the slow path ThreadLocalMap.getEntryAfterMiss()
    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<TransactionHolder> currentTransaction =
            new ThreadLocal<TransactionHolder>() {
                @Override
                protected TransactionHolder initialValue() {
                    return new TransactionHolder();
                }
            };

    @Nullable
    Transaction getCurrentTransaction() {
        return currentTransaction.get().transaction;
    }

    void addTransaction(Transaction transaction) {
        currentTransaction.get().transaction = transaction;
        transactions.add(transaction);
    }

    void removeTransaction(Transaction transaction) {
        currentTransaction.get().transaction = null;
        transactions.remove(transaction);
    }

    public Collection<Transaction> getTransactions() {
        return transactions;
    }

    private static class TransactionHolder {
        private @Nullable Transaction transaction;
    }
}
