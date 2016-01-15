/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.init;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.StandardSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.wire.api.model.CollectorServiceOuterClass.ProcessInfo;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

public class ProcessInfoCreator {

    private static final Logger logger = LoggerFactory.getLogger(ProcessInfoCreator.class);

    private ProcessInfoCreator() {}

    public static ProcessInfo create(String glowrootVersion) {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        Long processId = parseProcessId(ManagementFactory.getRuntimeMXBean().getName());
        String jvm = "";
        String javaVmName = StandardSystemProperty.JAVA_VM_NAME.value();
        if (javaVmName != null) {
            jvm = javaVmName + " (" + StandardSystemProperty.JAVA_VM_VERSION.value() + ", "
                    + System.getProperty("java.vm.info") + ")";
        }
        String java = "";
        String javaVersion = StandardSystemProperty.JAVA_VERSION.value();
        if (javaVersion != null) {
            java = "version " + javaVersion + ", vendor "
                    + StandardSystemProperty.JAVA_VM_VENDOR.value();
        }
        String heapDumpPath = getHeapDumpPathFromCommandLine();
        if (heapDumpPath == null) {
            String javaTempDir =
                    MoreObjects.firstNonNull(StandardSystemProperty.JAVA_IO_TMPDIR.value(), ".");
            heapDumpPath = new File(javaTempDir).getAbsolutePath();
        }
        ProcessInfo.Builder builder = ProcessInfo.newBuilder();
        try {
            builder.setHostName(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            logger.warn(e.getMessage(), e);
        }
        if (processId != null) {
            builder.setProcessId(OptionalInt64.newBuilder().setValue(processId).build());
        }
        return builder.setStartTime(runtimeMXBean.getStartTime())
                .setJvm(jvm)
                .setJava(java)
                .addAllJvmArg(runtimeMXBean.getInputArguments())
                .setHeapDumpDefaultDir(heapDumpPath)
                .setGlowrootAgentVersion(glowrootVersion)
                .build();
    }

    @VisibleForTesting
    static @Nullable Long parseProcessId(String runtimeName) {
        int index = runtimeName.indexOf('@');
        if (index > 0) {
            String pid = runtimeName.substring(0, index);
            try {
                return Long.parseLong(pid);
            } catch (NumberFormatException e) {
                logger.debug(e.getMessage(), e);
                return null;
            }
        } else {
            return null;
        }
    }

    private static @Nullable String getHeapDumpPathFromCommandLine() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        for (String arg : runtimeMXBean.getInputArguments()) {
            if (arg.startsWith("-XX:HeapDumpPath=")) {
                return arg.substring("-XX:HeapDumpPath=".length());
            }
        }
        return null;
    }
}
