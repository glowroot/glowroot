/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.util;

import java.util.Collection;
import java.util.Set;

import org.informantproject.core.MainEntryPoint;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;

/**
 * Only used by tests. It is important that Informant doesn't use any non-daemon threads which could
 * prevent the monitored JVM from shutting down propertly. It is also important that Informant shuts
 * down all threads on {@link MainEntryPoint#shutdown()} so that subsequent unit tests can truly
 * start from a clean state (no extraneous threads still running).
 * 
 * The placement of this class in the main Informant code base (and not inside of the tests folder)
 * is not ideal, but the alternative is to create a separate artifact (or at least classifier) for
 * this one class (now two classes, see also {@link Files}), which also seems to be not ideal.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class Threads {

    private Threads() {}

    public static Set<Thread> currentThreadList() {
        return Thread.getAllStackTraces().keySet();
    }

    // ensure the test didn't create any non-daemon threads
    public static void preShutdownCheck(final Set<Thread> preExistingThreads) {
        Collection<Thread> rogueThreads = Collections2.filter(currentThreadList(),
                new Predicate<Thread>() {
                    public boolean apply(Thread input) {
                        return input != Thread.currentThread() && !input.isDaemon()
                                && !preExistingThreads.contains(input);
                    }
                });
        if (!rogueThreads.isEmpty()) {
            throw new RogueThreadsException(rogueThreads);
        }
    }

    // ensure the test shutdown all threads that it created
    public static void postShutdownCheck(final Set<Thread> preExistingThreads)
            throws InterruptedException {

        // give it 5 seconds to shutdown threads
        long startedAt = System.currentTimeMillis();
        while (true) {
            Collection<Thread> rogueThreads = Collections2.filter(currentThreadList(),
                    Predicates.not(Predicates.in(preExistingThreads)));
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
}
