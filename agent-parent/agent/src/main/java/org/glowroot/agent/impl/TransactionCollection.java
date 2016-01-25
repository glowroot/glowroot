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

import java.util.Iterator;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import org.glowroot.agent.model.Transaction;

import static com.google.common.base.Preconditions.checkNotNull;

public class TransactionCollection implements Iterable<Transaction> {

    private final TransactionEntry head = new TransactionEntry(null);

    // tail is non-volatile since only accessed under lock
    private TransactionEntry tail = head;

    // all structural changes are made under lock for simplicity
    // TODO implement lock free structure
    private final Object lock = new Object();

    public TransactionEntry add(Transaction transaction) {
        TransactionEntry newTailEntry = new TransactionEntry(transaction);
        synchronized (lock) {
            tail.next = newTailEntry;
            newTailEntry.prev = tail;
            tail = newTailEntry;
        }
        return newTailEntry;
    }

    @Override
    public Iterator<Transaction> iterator() {
        return new Iterator<Transaction>() {
            private @Nullable TransactionEntry next = head.next;
            @Override
            public boolean hasNext() {
                return next != null;
            }
            @Override
            public Transaction next() {
                TransactionEntry curr = next;
                if (curr == null) {
                    throw new NoSuchElementException();
                }
                next = curr.next;
                return checkNotNull(curr.transaction);
            }
            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public class TransactionEntry {

        private final @Nullable Transaction transaction; // only null for head

        // prev is non-volatile since only accessed under lock
        private @Nullable TransactionEntry prev;

        // next is volatile since accessed by iterator outside of lock
        private volatile @Nullable TransactionEntry next;

        private TransactionEntry(@Nullable Transaction transaction) {
            this.transaction = transaction;
        }

        public void remove() {
            checkNotNull(prev);
            synchronized (lock) {
                prev.next = next;
                if (next != null) {
                    next.prev = prev;
                }
                if (this == tail) {
                    tail = prev;
                }
            }
        }
    }
}
