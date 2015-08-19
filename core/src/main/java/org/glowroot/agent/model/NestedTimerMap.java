/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.agent.model;

import javax.annotation.Nullable;

// micro-optimized map for nested timers
// TODO get rid of Entry objects, e.g. gs collection's UnifiedMap
class NestedTimerMap {

    // table length must always be a power of 2, see comment in get()
    //
    // TODO reduce initial capacity (to reduce memory in typical case of few entries) and add
    // resizing (to avoid collisions in atypical case of lots of entries)
    private final @Nullable Entry[] table = new Entry[16];

    @Nullable
    TimerImpl get(TimerNameImpl timerName) {
        // this mask requires table length to be a power of 2
        int bucket = timerName.specialHashCode() & (table.length - 1);
        Entry entry = table[bucket];
        while (true) {
            if (entry == null) {
                return null;
            }
            if (entry.timerName == timerName) {
                return entry.timer;
            }
            entry = entry.nextEntry;
        }
    }

    void put(TimerNameImpl timerName, TimerImpl timer) {
        Entry newEntry = new Entry(timerName, timer);
        int bucket = timerName.specialHashCode() & (table.length - 1);
        Entry entry = table[bucket];
        if (entry == null) {
            table[bucket] = newEntry;
            return;
        }
        Entry nextEntry;
        while ((nextEntry = entry.nextEntry) != null) {
            entry = nextEntry;
        }
        entry.nextEntry = newEntry;
    }

    private static class Entry {

        private final TimerNameImpl timerName;
        private final TimerImpl timer;
        private @Nullable Entry nextEntry;

        private Entry(TimerNameImpl timerName, TimerImpl timer) {
            this.timerName = timerName;
            this.timer = timer;
        }
    }
}
