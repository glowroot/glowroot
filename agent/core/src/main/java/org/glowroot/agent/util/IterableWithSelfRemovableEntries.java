/*
 * Copyright 2016-2026 the original author or authors.
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
package org.glowroot.agent.util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.checkerframework.checker.nullness.qual.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

public class IterableWithSelfRemovableEntries<E> implements Iterable<E> {

    private final ReferenceQueue<E> queue = new ReferenceQueue<E>();

    private final Entry headEntry = new Entry(null, queue);

    // tail is non-volatile since only accessed under lock
    private Entry tailEntry = headEntry;

    // all structural changes are made under lock for simplicity
    // TODO implement lock free structure
    private final Object lock = new Object();

    public SelfRemovableEntry add(E e) {
        Entry newTailEntry = new Entry(e, queue);
        synchronized (lock) {
            expungeStaleEntries();
            tailEntry.nextEntry = newTailEntry;
            newTailEntry.prevEntry = tailEntry;
            tailEntry = newTailEntry;
        }
        return newTailEntry;
    }

    @Override
    public Iterator<E> iterator() {
        synchronized (lock) {
            expungeStaleEntries();
        }
        return new ElementIterator();
    }

    // package-private for tests (#1110)
    int linkedEntryCountForTest() {
        synchronized (lock) {
            expungeStaleEntries();
            int count = 0;
            Entry currEntry = headEntry.nextEntry;
            while (currEntry != null) {
                count++;
                currEntry = currEntry.nextEntry;
            }
            return count;
        }
    }

    // Clears referents via WeakReference.clear() which does not enqueue on ReferenceQueue,
    // reproducing the pending-window case where get() is null but poll() is empty (#1110).
    void clearReferentsForTest() {
        synchronized (lock) {
            Entry currEntry = headEntry.nextEntry;
            while (currEntry != null) {
                if (currEntry.ref != null) {
                    currEntry.ref.clear();
                }
                currEntry = currEntry.nextEntry;
            }
        }
    }

    // requires lock
    private void expungeStaleEntries() {
        // Drain ReferenceQueue if the JVM has enqueued cleared refs. Always walk the list as well:
        // WeakReference.get() can already be null while the ref is still pending on the queue
        // (or cleared without enqueue), which used to early-return and leave tombstones (#1110).
        while (queue.poll() != null) {
        }
        Entry currEntry = headEntry.nextEntry;
        while (currEntry != null) {
            Entry next = currEntry.nextEntry;
            if (currEntry.getElement() == null) {
                currEntry.remove();
            }
            currEntry = next;
        }
    }

    public interface SelfRemovableEntry {
        void remove();
    }

    private class ElementIterator implements Iterator<E> {

        private @Nullable Entry nextEntry;
        private @Nullable E nextElement;

        @SuppressWarnings("method.invocation.invalid")
        private ElementIterator() {
            nextEntry = headEntry;
            advance();
        }

        @Override
        public boolean hasNext() {
            return nextElement != null;
        }

        @Override
        public E next() {
            E currElement = nextElement;
            if (currElement == null) {
                throw new NoSuchElementException();
            }
            advance();
            return currElement;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        private void advance() {
            advanceOne();
            while (nextElement == null && nextEntry != null) {
                advanceOne();
            }
        }

        private void advanceOne() {
            checkNotNull(nextEntry);
            nextEntry = nextEntry.nextEntry;
            nextElement = nextEntry == null ? null : nextEntry.getElement();
        }
    }

    private class Entry implements SelfRemovableEntry {

        private final @Nullable WeakReference<E> ref; // only null for head

        // prev is non-volatile since only accessed under lock
        private @Nullable Entry prevEntry; // only null for head and removed

        // next is volatile since accessed by iterator outside of lock
        private volatile @Nullable Entry nextEntry;

        private Entry(@Nullable E e, ReferenceQueue<E> queue) {
            if (e == null) {
                ref = null;
            } else {
                ref = new WeakReference<E>(e, queue);
            }
        }

        @Override
        public void remove() {
            synchronized (lock) {
                if (prevEntry == null) {
                    // already removed
                    return;
                }
                Entry localPrevEntry = checkNotNull(prevEntry);
                localPrevEntry.nextEntry = nextEntry;
                if (nextEntry != null) {
                    nextEntry.prevEntry = localPrevEntry;
                }
                if (this == tailEntry) {
                    tailEntry = localPrevEntry;
                }
                prevEntry = null;
            }
        }

        private @Nullable E getElement() {
            if (ref == null) {
                return null;
            }
            return ref.get();
        }
    }
}
