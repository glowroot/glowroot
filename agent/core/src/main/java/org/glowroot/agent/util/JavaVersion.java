/*
 * Copyright 2014-2018 the original author or authors.
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
package org.glowroot.agent.util;

import com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.Nullable;

// LIMIT DEPENDENCY USAGE IN THIS CLASS SO IT DOESN'T TRIGGER ANY CLASS LOADING ON ITS OWN
public class JavaVersion {

    private static final boolean IS_JAVA_6;
    private static final boolean IS_GREATER_THAN_OR_EQUAL_TO_JAVA_9;

    private static final boolean IBM_JVM;
    private static final boolean JROCKIT_JVM;

    static {
        String javaVersion = System.getProperty("java.version");
        IS_JAVA_6 = parseIsJava6(javaVersion);
        IS_GREATER_THAN_OR_EQUAL_TO_JAVA_9 = parseIsGreaterThanOrEqualToJava9(javaVersion);

        String javaVmName = System.getProperty("java.vm.name");
        IBM_JVM = "IBM J9 VM".equals(javaVmName);
        JROCKIT_JVM = "Oracle JRockit(R)".equals(javaVmName);
    }

    private JavaVersion() {}

    public static boolean isJava6() {
        return IS_JAVA_6;
    }

    public static boolean isGreaterThanOrEqualToJava9() {
        return IS_GREATER_THAN_OR_EQUAL_TO_JAVA_9;
    }

    public static boolean isIbmJvm() {
        return IBM_JVM;
    }

    public static boolean isJRockitJvm() {
        return JROCKIT_JVM;
    }

    @VisibleForTesting
    static boolean parseIsJava6(@Nullable String javaVersion) {
        return javaVersion != null && javaVersion.startsWith("1.6");
    }

    @VisibleForTesting
    static boolean parseIsGreaterThanOrEqualToJava9(@Nullable String javaVersion) {
        return javaVersion != null && !javaVersion.startsWith("1.");
    }
}
