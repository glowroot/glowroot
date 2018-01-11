/*
 * Copyright 2015-2017 the original author or authors.
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
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.JvmConfig;
import org.glowroot.agent.live.LiveJvmServiceImpl;
import org.glowroot.common.util.SystemProperties;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.HostInfo;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.JavaInfo;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.ProcessInfo;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

public class EnvironmentCreator {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentCreator.class);

    private EnvironmentCreator() {}

    public static Environment create(String glowrootVersion, JvmConfig jvmConfig) {
        HostInfo hostInfo = createHostInfo();
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        ProcessInfo processInfo = createProcessInfo(runtimeMXBean);
        JavaInfo javaInfo = createJavaInfo(glowrootVersion, jvmConfig, runtimeMXBean);
        return Environment.newBuilder()
                .setHostInfo(hostInfo)
                .setProcessInfo(processInfo)
                .setJavaInfo(javaInfo)
                .build();
    }

    private static HostInfo createHostInfo() {
        OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        HostInfo.Builder hostInfo = HostInfo.newBuilder();
        try {
            hostInfo.setHostName(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            logger.warn(e.getMessage(), e);
        }
        hostInfo.setAvailableProcessors(operatingSystemMXBean.getAvailableProcessors());
        Long totalPhysicalMemoryBytes = getTotalPhysicalMemoryBytes(operatingSystemMXBean);
        if (totalPhysicalMemoryBytes != null) {
            hostInfo.setTotalPhysicalMemoryBytes(
                    OptionalInt64.newBuilder().setValue(totalPhysicalMemoryBytes));
        }
        return hostInfo.setOsName(operatingSystemMXBean.getName())
                .setOsVersion(operatingSystemMXBean.getVersion())
                .build();
    }

    private static ProcessInfo createProcessInfo(RuntimeMXBean runtimeMXBean) {
        Long processId = LiveJvmServiceImpl.getProcessId();
        ProcessInfo.Builder processInfo = ProcessInfo.newBuilder();
        if (processId != null) {
            processInfo.setProcessId(OptionalInt64.newBuilder().setValue(processId).build());
        }
        processInfo.setStartTime(runtimeMXBean.getStartTime());
        return processInfo.build();
    }

    private static JavaInfo createJavaInfo(String glowrootVersion, JvmConfig jvmConfig,
            RuntimeMXBean runtimeMXBean) {
        String jvm = "";
        String javaVmName = StandardSystemProperty.JAVA_VM_NAME.value();
        if (javaVmName != null) {
            jvm = javaVmName + " (" + StandardSystemProperty.JAVA_VM_VERSION.value() + ", "
                    + System.getProperty("java.vm.info") + ")";
        }
        String javaVersion = StandardSystemProperty.JAVA_VERSION.value();
        String heapDumpPath = getHeapDumpPathFromCommandLine();
        if (heapDumpPath == null) {
            String javaTempDir =
                    MoreObjects.firstNonNull(StandardSystemProperty.JAVA_IO_TMPDIR.value(), ".");
            heapDumpPath = new File(javaTempDir).getAbsolutePath();
        }
        return JavaInfo.newBuilder()
                .setVersion(Strings.nullToEmpty(javaVersion))
                .setVm(jvm)
                .addAllArg(SystemProperties.maskJvmArgs(runtimeMXBean.getInputArguments(),
                        jvmConfig.maskSystemProperties()))
                .setHeapDumpDefaultDir(heapDumpPath)
                .setGlowrootAgentVersion(glowrootVersion)
                .build();
    }

    @VisibleForTesting
    static @Nullable Long getTotalPhysicalMemoryBytes(OperatingSystemMXBean operatingSystemMXBean) {
        Class<?> sunClass;
        try {
            sunClass = Class.forName("com.sun.management.OperatingSystemMXBean");
        } catch (ClassNotFoundException e) {
            logger.debug(e.getMessage(), e);
            return null;
        }
        Method method;
        try {
            method = sunClass.getMethod("getTotalPhysicalMemorySize");
        } catch (SecurityException e) {
            logger.debug(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.debug(e.getMessage(), e);
            return null;
        }
        method.setAccessible(true);
        try {
            return (Long) method.invoke(operatingSystemMXBean);
        } catch (IllegalArgumentException e) {
            logger.debug(e.getMessage(), e);
            return null;
        } catch (IllegalAccessException e) {
            logger.debug(e.getMessage(), e);
            return null;
        } catch (InvocationTargetException e) {
            logger.debug(e.getMessage(), e);
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
