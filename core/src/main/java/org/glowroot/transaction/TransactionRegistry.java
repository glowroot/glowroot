/*
 * Copyright 2011-2014 the original author or authors.
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

import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.markers.Singleton;
import org.glowroot.transaction.model.CurrentTransactionMetricHolder;
import org.glowroot.transaction.model.Transaction;

/**
 * Registry to hold all active transactions.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TransactionRegistry {

    // collection of active running transactions, "nearly" ordered by start time
    // ordering is not completely guaranteed since there is no synchronization block around
    // transaction instantiation and placement into the registry
    private final Collection<Transaction> transactions = Sets.newConcurrentHashSet();

    // active running transaction being executed by the current thread
    private final ThreadLocal</*@Nullable*/Transaction> currentTransactionHolder =
            new ThreadLocal</*@Nullable*/Transaction>();

    // active transaction metric being executed by the current thread
    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<CurrentTransactionMetricHolder> currentTransactionMetricHolder =
            new ThreadLocal<CurrentTransactionMetricHolder>() {
                @Override
                protected CurrentTransactionMetricHolder initialValue() {
                    return new CurrentTransactionMetricHolder();
                }
            };

    @Nullable
    Transaction getCurrentTransaction() {
        return currentTransactionHolder.get();
    }

    CurrentTransactionMetricHolder getCurrentTransactionMetricHolder() {
        return currentTransactionMetricHolder.get();
    }

    void addTransaction(Transaction transaction) {
        currentTransactionHolder.set(transaction);
        transactions.add(transaction);
    }

    void removeTransaction(Transaction transaction) {
        currentTransactionHolder.remove();
        transactions.remove(transaction);
    }

    public Collection<Transaction> getTransactions() {
        return transactions;
    }
}
