/*
 * Copyright 2013-2015 the original author or authors.
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

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ProcessId {

    private static final Logger logger = LoggerFactory.getLogger(ProcessId.class);

    private ProcessId() {}

    static @Nullable String getProcessId() {
        return parseProcessId(ManagementFactory.getRuntimeMXBean().getName());
    }

    @VisibleForTesting
    static @Nullable String parseProcessId(String runtimeName) {
        int index = runtimeName.indexOf('@');
        if (index > 0) {
            String pid = runtimeName.substring(0, index);
            try {
                Long.parseLong(pid);
                return pid;
            } catch (NumberFormatException e) {
                logger.debug(e.getMessage(), e);
                return null;
            }
        } else {
            return null;
        }
    }
}
