/**
 * Copyright 2016-2019 the original author or authors.
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

import static org.glowroot.testing.JavaVersion.JAVA6;
import static org.glowroot.testing.JavaVersion.JAVA7;
import static org.glowroot.testing.JavaVersion.JAVA8;

public class Spring {

    private static final String MODULE_PATH = "agent/plugins/spring-plugin";

    public static void main(String[] args) throws Exception {
        for (int i = 0; i <= 7; i++) {
            run("3.0." + i + ".RELEASE", "spring-3.x");
        }
        for (int i = 0; i <= 4; i++) {
            run("3.1." + i + ".RELEASE", "spring-3.x");
        }
        // 3.2.0 and 3.2.1 fail badly under Java 8
        // due to https://jira.spring.io/browse/SPR-10292
        // which was actually fixed in 3.2.2 by
        // https://github.com/spring-projects/spring-framework/commit/6d77f1cf3b3f060ead70d49079bc87d75e0b105c
        runNotJava8("3.2.0.RELEASE", "spring-3.2.x");
        runNotJava8("3.2.1.RELEASE", "spring-3.2.x");
        for (int i = 2; i <= 18; i++) {
            run("3.2." + i + ".RELEASE", "spring-3.2.x");
        }
        run("4.0.0.RELEASE", "spring-4.0.0");
        for (int i = 1; i <= 9; i++) {
            run("4.0." + i + ".RELEASE", "spring-4.x");
        }
        for (int i = 0; i <= 9; i++) {
            run("4.1." + i + ".RELEASE", "spring-4.x");
        }
        for (int i = 0; i <= 9; i++) {
            run("4.2." + i + ".RELEASE", "spring-4.x");
        }
        for (int i = 0; i <= 22; i++) {
            run("4.3." + i + ".RELEASE", "spring-4.x");
        }
        runJava8("5.0.0.RELEASE", "0.7.0.RELEASE", "4.1.15.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.1.RELEASE", "0.7.1.RELEASE", "4.1.16.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.2.RELEASE", "0.7.1.RELEASE", "4.1.16.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.3.RELEASE", "0.7.3.RELEASE", "4.1.20.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.4.RELEASE", "0.7.4.RELEASE", "4.1.21.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.5.RELEASE", "0.7.6.RELEASE", "4.1.22.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.6.RELEASE", "0.7.7.RELEASE", "4.1.24.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.7.RELEASE", "0.7.8.RELEASE", "4.1.25.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.8.RELEASE", "0.7.8.RELEASE", "4.1.25.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.9.RELEASE", "0.7.9.RELEASE", "4.1.29.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.10.RELEASE", "0.7.9.RELEASE", "4.1.29.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.11.RELEASE", "0.7.12.RELEASE", "4.1.31.Final", "spring-4.x", "spring-5.0.x");
        runJava8("5.0.12.RELEASE", "0.7.14.RELEASE", "4.1.32.Final", "spring-4.x", "spring-5.0.x");

        runJava8("5.1.0.RELEASE", "0.8.0.RELEASE", "4.1.29.Final", "spring-4.x", "spring-5.1.x");
        runJava8("5.1.1.RELEASE", "0.8.1.RELEASE", "4.1.29.Final", "spring-4.x", "spring-5.1.x");
        runJava8("5.1.2.RELEASE", "0.8.2.RELEASE", "4.1.29.Final", "spring-4.x", "spring-5.1.x");
        runJava8("5.1.3.RELEASE", "0.8.3.RELEASE", "4.1.31.Final", "spring-4.x", "spring-5.1.x");
        runJava8("5.1.4.RELEASE", "0.8.4.RELEASE", "4.1.32.Final", "spring-4.x", "spring-5.1.x");
    }

    private static void run(String version, String... profiles) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "spring.version", version);
        Util.runTests(MODULE_PATH, profiles, JAVA8, JAVA7, JAVA6);
    }

    private static void runNotJava8(String version, String... profiles) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "spring.version", version);
        Util.runTests(MODULE_PATH, profiles, JAVA7, JAVA6);
    }

    private static void runJava8(String version, String reactorVersion, String nettyVersion,
            String... profiles) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "spring.version", version);
        Util.updateLibVersion(MODULE_PATH, "reactor.version", reactorVersion);
        Util.updateLibVersion(MODULE_PATH, "netty.version", nettyVersion);
        Util.runTests(MODULE_PATH, profiles, JAVA8);
    }
}
