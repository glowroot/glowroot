/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.informantproject.MainEntryPoint;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;

/**
 * Only used by tests. It is important that Informant doesn't use any non-daemon threads which could
 * prevent the monitored JVM from shutting down propertly. It is also important that Informant shuts
 * down all threads on {@link MainEntryPoint#shutdown()} so that subsequent unit tests can truly
 * start from a clean state (no extraneous threads still running).
 * 
 * The placement of this class in the main Informant code base (and not inside of the tests folder)
 * is not ideal, but the alternative is to create a separate artifact (or at least classifier) for
 * this one class, which also seems to be not ideal.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class ThreadChecker {

    private ThreadChecker() {}

    public static Set<Thread> currentThreadList() {
        return Thread.getAllStackTraces().keySet();
    }

    public static void postShutdownThreadCheck(Set<Thread> preExistingThreads)
            throws InterruptedException {

        List<Thread> rogueThreads = null;
        // give it 5?? seconds to shutdown threads
        for (int i = 0; i < 500; i++) {
            rogueThreads = new ArrayList<Thread>();
            for (Thread thread : currentThreadList()) {
                if (!preExistingThreads.contains(thread)) {
                    rogueThreads.add(thread);
                }
            }
            if (rogueThreads.isEmpty()) {
                // success
                break;
            }
            // failure, wait a few milliseconds and try again
            Thread.sleep(10);
        }
        if (!rogueThreads.isEmpty()) {
            throw new RogueThreadsException(rogueThreads);
        }
    }

    public static void preShutdownNonDaemonThreadCheck(Set<Thread> preExistingThreads) {
        List<Thread> rogueThreads = new ArrayList<Thread>();
        for (Thread thread : currentThreadList()) {
            if (thread != Thread.currentThread() && !thread.isDaemon()
                    && !preExistingThreads.contains(thread)) {
                rogueThreads.add(thread);
            }
        }
        if (!rogueThreads.isEmpty()) {
            throw new RogueThreadsException(rogueThreads);
        }
    }

    @SuppressWarnings("serial")
    public static class RogueThreadsException extends RuntimeException {
        private final List<Thread> rogueThreads;
        private RogueThreadsException(List<Thread> rogueThreads) {
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
