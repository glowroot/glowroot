/*
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
package io.informant.container;

import java.lang.Thread.State;
import java.util.Collection;
import java.util.List;

import checkers.igj.quals.ReadOnly;
import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

import io.informant.markers.Static;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class Threads {

    private Threads() {}

    public static List<Thread> currentThreads() {
        List<Thread> threads = Lists.newArrayList();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            // DestroyJavaVM thread seems a bit unpredictable, easier to just filter it out
            if (thread.getState() != State.TERMINATED
                    && !thread.getName().equals("DestroyJavaVM")) {
                threads.add(thread);
            }
        }
        return threads;
    }

    // ensure the test didn't create any non-daemon threads
    public static void preShutdownCheck(@ReadOnly Collection<Thread> preExistingThreads)
            throws InterruptedException {
        // give the test 5 seconds to shutdown any threads they may have created, e.g. give tomcat
        // time to shutdown when testing tomcat plugin
        Stopwatch stopwatch = new Stopwatch().start();
        List<Thread> nonPreExistingThreads;
        List<Thread> rogueThreads;
        do {
            nonPreExistingThreads = getNonPreExistingThreads(preExistingThreads);
            rogueThreads = Lists.newArrayList();
            for (Thread thread : nonPreExistingThreads) {
                if (isRogueThread(thread)) {
                    rogueThreads.add(thread);
                }
            }
            // check total number of threads to make sure Informant is not creating too many
            //
            // currently, the six threads are:
            //
            // Informant-Background-0
            // Informant-Background-1
            // H2 Log Writer INFORMANT
            // H2 File Lock Watchdog <lock db file>
            // Informant-Http-Boss
            // Informant-Http-Worker-0
            if (rogueThreads.isEmpty() && nonPreExistingThreads.size() <= 6) {
                // success
                return;
            }
            // wait a few milliseconds before trying again
            Thread.sleep(10);
        } while (stopwatch.elapsed(SECONDS) < 5);
        // failure
        if (!rogueThreads.isEmpty()) {
            throw new RogueThreadsException(rogueThreads);
        } else {
            throw new TooManyThreadsException(nonPreExistingThreads);
        }
    }

    // ensure the test shutdown all threads that it created
    public static void postShutdownCheck(@ReadOnly Collection<Thread> preExistingThreads)
            throws InterruptedException {
        // give it 5 seconds to shutdown threads
        Stopwatch stopwatch = new Stopwatch().start();
        List<Thread> rogueThreads;
        do {
            rogueThreads = getNonPreExistingThreads(preExistingThreads);
            if (rogueThreads.isEmpty()) {
                // success
                return;
            }
            // wait a few milliseconds before trying again
            Thread.sleep(10);
        } while (stopwatch.elapsed(SECONDS) < 5);
        // failure
        throw new RogueThreadsException(rogueThreads);
    }

    // try to handle under- and over- sleeping for tests that depend on more accurate sleep timing
    public static void moreAccurateSleep(int millis) throws InterruptedException {
        Stopwatch stopwatch = new Stopwatch().start();
        if (millis > 10) {
            Thread.sleep(millis - 10);
        }
        while (stopwatch.elapsed(MILLISECONDS) < millis) {
            Thread.sleep(1);
        }
    }

    private static List<Thread> getNonPreExistingThreads(
            @ReadOnly Collection<Thread> preExistingThreads) {
        List<Thread> currentThreads = currentThreads();
        currentThreads.removeAll(preExistingThreads);
        // remove current thread in case it is newly created by the tests
        // (e.g. SocketCommandProcessor)
        currentThreads.remove(Thread.currentThread());
        return currentThreads;
    }

    private static boolean isRogueThread(Thread thread) {
        if (!thread.isDaemon()) {
            return true;
        } else if (isShaded() && !thread.getName().startsWith("Informant-")) {
            return true;
        } else if (!isShaded()
                && !thread.getName().startsWith("Informant-")
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
        try {
            Class.forName("io.informant.shaded.h2.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @SuppressWarnings("serial")
    public static class RogueThreadsException extends ThreadsException {
        private RogueThreadsException(Collection<Thread> threads) {
            super(threads);
        }
    }

    @SuppressWarnings("serial")
    public static class TooManyThreadsException extends ThreadsException {
        private TooManyThreadsException(Collection<Thread> threads) {
            super(threads);
        }
    }

    @SuppressWarnings("serial")
    public static class ThreadsException extends RuntimeException {
        private final Collection<Thread> threads;
        private ThreadsException(Collection<Thread> threads) {
            this.threads = threads;
        }
        @Override
        public String getMessage() {
            StringBuilder sb = new StringBuilder();
            for (Thread thread : threads) {
                sb.append(threadToString(thread));
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
}
