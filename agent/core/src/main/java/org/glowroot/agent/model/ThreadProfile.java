/*
 * Copyright 2011-2017 the original author or authors.
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

import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.List;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.common.model.MutableProfile;

public class ThreadProfile {

    private final Object lock = new Object();
    @GuardedBy("lock")
    private final List<List<StackTraceElement>> unmergedStackTraces = Lists.newArrayList();
    @GuardedBy("lock")
    private final List<Thread.State> unmergedStackTraceThreadStates = Lists.newArrayList();
    @GuardedBy("lock")
    private @MonotonicNonNull MutableProfile profile;
    @GuardedBy("lock")
    private long sampleCount;

    @VisibleForTesting
    public ThreadProfile() {}

    public void mergeInto(MutableProfile profile) {
        synchronized (lock) {
            if (this.profile == null) {
                mergeTheUnmergedInto(profile);
            } else {
                profile.merge(this.profile);
            }
        }
    }

    public org.glowroot.wire.api.model.ProfileOuterClass.Profile toProto() {
        synchronized (lock) {
            if (profile == null) {
                profile = new MutableProfile();
                mergeTheUnmergedInto(profile);
                unmergedStackTraces.clear();
                unmergedStackTraceThreadStates.clear();
            }
            return profile.toProto();
        }
    }

    public long getSampleCount() {
        // lock is needed for visibility
        synchronized (lock) {
            return sampleCount;
        }
    }

    // limit is just to cap memory consumption for a single transaction profile in case it runs for
    // a very very very long time
    public void addStackTrace(ThreadInfo threadInfo, int limit) {
        synchronized (lock) {
            if (sampleCount >= limit) {
                return;
            }
            List<StackTraceElement> stackTrace = Arrays.asList(threadInfo.getStackTrace());
            Thread.State threadState = threadInfo.getThreadState();
            if (profile == null) {
                unmergedStackTraces.add(stackTrace);
                unmergedStackTraceThreadStates.add(threadState);
                if (unmergedStackTraces.size() >= 10) {
                    // merged stack tree takes up less memory
                    profile = new MutableProfile();
                    mergeTheUnmergedInto(profile);
                    unmergedStackTraces.clear();
                    unmergedStackTraceThreadStates.clear();
                }
            } else {
                profile.merge(stackTrace, threadState);
            }
            sampleCount++;
        }
    }

    private void mergeTheUnmergedInto(MutableProfile profile) {
        for (int i = 0; i < unmergedStackTraces.size(); i++) {
            List<StackTraceElement> stackTrace = unmergedStackTraces.get(i);
            Thread.State threadState = unmergedStackTraceThreadStates.get(i);
            profile.merge(stackTrace, threadState);
        }
    }
}
