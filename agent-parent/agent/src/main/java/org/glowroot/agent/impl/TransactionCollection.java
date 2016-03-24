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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import org.glowroot.agent.model.Transaction;

import static com.google.common.base.Preconditions.checkNotNull;

public class TransactionCollection implements Iterable<Transaction> {

    private final ReferenceQueue<Transaction> queue = new ReferenceQueue<Transaction>();

    private final TransactionEntry headEntry = new TransactionEntry(null, queue);

    // tail is non-volatile since only accessed under lock
    private TransactionEntry tailEntry = headEntry;

    // all structural changes are made under lock for simplicity
    // TODO implement lock free structure
    private final Object lock = new Object();

    public TransactionEntry add(Transaction transaction) {
        TransactionEntry newTailEntry = new TransactionEntry(transaction, queue);
        synchronized (lock) {
            expungeStaleEntries();
            tailEntry.nextEntry = newTailEntry;
            newTailEntry.prevEntry = tailEntry;
            tailEntry = newTailEntry;
        }
        return newTailEntry;
    }

    @Override
    public Iterator<Transaction> iterator() {
        synchronized (lock) {
            expungeStaleEntries();
        }
        return new TransactionIterator();
    }

    // requires lock
    private void expungeStaleEntries() {
        Reference<? extends Transaction> ref = queue.poll();
        if (ref == null) {
            return;
        }
        // drain the queue, since going to loop over and clean up everything anyways
        while (queue.poll() != null) {
        }
        TransactionEntry currEntry = headEntry.nextEntry;
        while (currEntry != null) {
            if (currEntry.getTransaction() == null) {
                currEntry.remove();
            }
            currEntry = currEntry.nextEntry;
        }
    }

    private class TransactionIterator implements Iterator<Transaction> {

        private @Nullable TransactionEntry nextEntry;
        private @Nullable Transaction nextTransaction;

        @SuppressWarnings("method.invocation.invalid")
        private TransactionIterator() {
            nextEntry = headEntry;
            advance();
        }

        @Override
        public boolean hasNext() {
            return nextTransaction != null;
        }

        @Override
        public Transaction next() {
            Transaction currTransaction = nextTransaction;
            if (currTransaction == null) {
                throw new NoSuchElementException();
            }
            advance();
            return currTransaction;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        private void advance() {
            advanceOne();
            while (nextTransaction == null && nextEntry != null) {
                advanceOne();
            }
        }

        private void advanceOne() {
            checkNotNull(nextEntry);
            nextEntry = nextEntry.nextEntry;
            nextTransaction = nextEntry == null ? null : nextEntry.getTransaction();
        }
    }

    public class TransactionEntry {

        private final @Nullable WeakReference<Transaction> transactionRef; // only null for head

        // prev is non-volatile since only accessed under lock
        private @Nullable TransactionEntry prevEntry;

        // next is volatile since accessed by iterator outside of lock
        private volatile @Nullable TransactionEntry nextEntry;

        private TransactionEntry(@Nullable Transaction transaction,
                ReferenceQueue<Transaction> queue) {
            if (transaction == null) {
                transactionRef = null;
            } else {
                transactionRef = new WeakReference<Transaction>(transaction, queue);
            }
        }

        public void remove() {
            synchronized (lock) {
                expungeStaleEntries();
                TransactionEntry localPrevEntry = checkNotNull(prevEntry);
                localPrevEntry.nextEntry = nextEntry;
                if (nextEntry != null) {
                    nextEntry.prevEntry = localPrevEntry;
                }
                if (this == tailEntry) {
                    tailEntry = localPrevEntry;
                }
            }
        }

        private @Nullable Transaction getTransaction() {
            if (transactionRef == null) {
                return null;
            }
            return transactionRef.get();
        }
    }
}
