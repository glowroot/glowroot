/*
 * Copyright 2013-2014 the original author or authors.
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

import javax.annotation.Nullable;

public class ProcessId {

    private static final @Nullable String pid;

    static {
        pid = initPid();
    }

    private ProcessId() {}

    public static @Nullable String getPid() {
        return pid;
    }

    private static @Nullable String initPid() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int index = runtimeName.indexOf('@');
        if (index > 0) {
            return runtimeName.substring(0, index);
        } else {
            return null;
        }
    }
}
