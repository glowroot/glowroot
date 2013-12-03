/*
 * Copyright 2013 the original author or authors.
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
package org.glowroot.jvm;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import org.glowroot.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class ThreadCpuTime {

    private static final ThreadMXBean threadMXBean;
    private static final boolean supported;

    static {
        threadMXBean = ManagementFactory.getThreadMXBean();
        supported = threadMXBean.isThreadCpuTimeSupported();
    }

    private ThreadCpuTime() {}

    public static long getCurrentThreadCpuTimeSafely() {
        if (!supported) {
            // getCurrentThreadCpuTime() throws UnsupportedOperationException in this case
            return -1;
        }
        return threadMXBean.getCurrentThreadCpuTime();
    }

    public static long getThreadCpuTimeSafely(long threadId) {
        if (!supported) {
            // getThreadCpuTime() throws UnsupportedOperationException in this case
            return -1;
        }
        return threadMXBean.getThreadCpuTime(threadId);
    }

    public static Availability getAvailability() {
        if (!supported) {
            return Availability.unavailable("java.lang.management.ThreadMXBean"
                    + ".isThreadCpuTimeSupported() returned false");
        } else if (!threadMXBean.isThreadCpuTimeEnabled()) {
            return Availability.unavailable("java.lang.management.ThreadMXBean"
                    + ".isThreadCpuTimeEnabled() returned false");
        } else {
            return Availability.available();
        }
    }
}
