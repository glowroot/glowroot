/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.core.util;

import java.lang.Thread.State;
import java.util.Collection;
import java.util.List;

import checkers.igj.quals.ReadOnly;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.Lists;

/**
 * The placement of this code in the main Informant code base (and not inside of the tests folder)
 * is not ideal, but the alternative is to create a separate artifact (or at least classifier) for
 * this small amount of code, which also seems to be not ideal.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@OnlyUsedByTests
@Static
public final class Threads {

    public static List<Thread> currentThreads() {
        List<Thread> threads = Lists.newArrayList();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getState() != State.TERMINATED) {
                threads.add(thread);
            }
        }
        return threads;
    }

    // ensure the test didn't create any non-daemon threads
    public static void preShutdownCheck(final @ReadOnly Collection<Thread> preExistingThreads)
            throws InterruptedException {

        // give the test 5 seconds to shutdown any threads they may have created, e.g. give tomcat
        // time to shutdown when testing tomcat plugin
        long startedAt = System.currentTimeMillis();
        while (true) {
            List<Thread> rogueThreads = Lists.newArrayList();
            for (Thread thread : currentThreads()) {
                if (thread != Thread.currentThread() && !preExistingThreads.contains(thread)
                        && isRogueThread(thread)) {
                    rogueThreads.add(thread);
                }
            }
            if (rogueThreads.isEmpty()) {
                // success
                return;
            } else if (System.currentTimeMillis() - startedAt > 5000) {
                throw new RogueThreadsException(rogueThreads);
            } else {
                // failure, wait a few milliseconds before trying again
                Thread.sleep(10);
            }
        }
    }

    // ensure the test shutdown all threads that it created
    public static void postShutdownCheck(@ReadOnly Collection<Thread> preExistingThreads)
            throws InterruptedException {

        // give it 5 seconds to shutdown threads
        long startedAt = System.currentTimeMillis();
        while (true) {
            List<Thread> rogueThreads = Lists.newArrayList(currentThreads());
            rogueThreads.removeAll(preExistingThreads);
            if (rogueThreads.isEmpty()) {
                // success
                return;
            } else if (System.currentTimeMillis() - startedAt > 5000) {
                throw new RogueThreadsException(rogueThreads);
            } else {
                // failure, wait a few milliseconds before trying again
                Thread.sleep(10);
            }
        }
    }

    private static boolean isRogueThread(Thread thread) {
        if (!thread.isDaemon()) {
            return true;
        } else if (isShaded() && !thread.getName().startsWith("Informant-")) {
            return true;
        } else if (!isShaded()
                && !(thread.getName().startsWith("Informant-") || thread.getName().startsWith(
                        "InformantTest-"))
                && !thread.getName().startsWith("H2 File Lock Watchdog ")
                && !thread.getName().startsWith("H2 Log Writer ")
                && !thread.getName().equals("Generate Seed")) {
            // the last one (Generate Seed) is an H2 thread (see org.h2.util.MathUtils) that is
            // usually completed by now (but was not on at least one occasion)
            return true;
        }
        return false;
    }

    private static boolean isShaded() {
        return org.h2.Driver.class.getName().startsWith("io.informant.shaded.");
    }

    @SuppressWarnings("serial")
    public static class RogueThreadsException extends RuntimeException {
        private final Collection<Thread> rogueThreads;
        private RogueThreadsException(Collection<Thread> rogueThreads) {
            this.rogueThreads = rogueThreads;
        }
        @Override
        public String getMessage() {
            StringBuilder sb = new StringBuilder();
            for (Thread rogueThread : rogueThreads) {
                sb.append(threadToString(rogueThread));
                sb.append("\n");
            }
            return sb.toString();
        }
        private static String threadToString(Thread thread) {
            ToStringHelper toStringHelper = Objects.toStringHelper(thread)
                    .add("name", thread.getName())
                    .add("class", thread.getClass().getName())
                    .add("state", thread.getState());
            for (int i = 0; i < Math.min(30, thread.getStackTrace().length); i++) {
                toStringHelper.add("stackTrace." + i, thread.getStackTrace()[i].getClassName());
            }
            return toStringHelper.toString();
        }
    }

    private Threads() {}
}
