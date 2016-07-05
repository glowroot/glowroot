/**
 * Copyright 2016 the original author or authors.
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
package org.glowroot.testing;

public class Spring {

    private static final String JAVA_7_HOME;

    private static final String MODULE_PATH = "agent-parent/plugins/spring-plugin";

    static {
        String value = System.getProperty("java7.home");
        if (value == null) {
            throw new IllegalStateException("Must provide -Djava7.home=...");
        }
        JAVA_7_HOME = value;
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i <= 7; i++) {
            run("3.0." + i + ".RELEASE", "spring-3.x");
        }
        for (int i = 0; i <= 4; i++) {
            run("3.1." + i + ".RELEASE", "spring-3.x");
        }
        String javaHome = System.getProperty("java.home");
        try {
            System.setProperty("java.home", JAVA_7_HOME);
            // 3.2.0 and 3.2.1 fail badly under Java 8
            // due to https://jira.spring.io/browse/SPR-10292
            // which was actually fixed in 3.2.2 by
            // https://github.com/spring-projects/spring-framework/commit/6d77f1cf3b3f060ead70d49079bc87d75e0b105c
            run("3.2.0.RELEASE", "spring-3.2.x");
            run("3.2.1.RELEASE", "spring-3.2.x");
        } finally {
            System.setProperty("java.home", javaHome);
        }
        for (int i = 2; i <= 16; i++) {
            run("3.2." + i + ".RELEASE", "spring-3.2.x");
        }
        for (int i = 0; i <= 9; i++) {
            run("4.0." + i + ".RELEASE");
        }
        for (int i = 0; i <= 9; i++) {
            run("4.1." + i + ".RELEASE");
        }
        for (int i = 0; i <= 7; i++) {
            run("4.2." + i + ".RELEASE");
        }
        run("4.3.0.RELEASE");
        run("4.3.1.RELEASE");
    }

    private static void run(String version, String... profiles) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "spring.version", version);
        Util.runTests(MODULE_PATH, profiles);
    }
}
