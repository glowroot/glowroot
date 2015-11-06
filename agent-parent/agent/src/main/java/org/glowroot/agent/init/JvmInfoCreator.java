/*
 * Copyright 2015 the original author or authors.
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

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

import com.google.common.base.StandardSystemProperty;

import org.glowroot.wire.api.model.JvmInfoOuterClass.JvmInfo;

public class JvmInfoCreator {

    private JvmInfoCreator() {}

    public static JvmInfo create() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
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
        return JvmInfo.newBuilder()
                .setStartTime(runtimeMXBean.getStartTime())
                .setJvm(jvm)
                .setJava(java)
                .addAllJvmArg(runtimeMXBean.getInputArguments())
                .build();
    }
}
