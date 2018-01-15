/**
 * Copyright 2016-2018 the original author or authors.
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

public class Hibernate {

    private static final String MODULE_PATH = "agent/plugins/hibernate-plugin";

    public static void main(String[] args) throws Exception {
        run("3.3.0.GA", "3.3.0.ga", "hibernate-3.x", "hibernate-3.3.0.ga");
        run("3.3.1.GA", "3.3.1.GA", "hibernate-3.x");
        run("3.3.2.GA", "3.3.1.GA", "hibernate-3.x");
        for (int i = 0; i <= 6; i++) {
            run("3.5." + i + "-Final", "3.5." + i + "-Final", "hibernate-3.x");
        }
        for (int i = 0; i <= 10; i++) {
            run("3.6." + i + ".Final", "", "hibernate-3.6.x");
        }
        run("4.0.0.Final");
        run("4.0.1.Final");
        for (int i = 0; i <= 12; i++) {
            run("4.1." + i + ".Final");
        }
        for (int i = 0; i <= 21; i++) {
            run("4.2." + i + ".Final");
        }
        for (int i = 0; i <= 11; i++) {
            run("4.3." + i + ".Final");
        }
        for (int i = 0; i <= 12; i++) {
            runJava7("5.0." + i + ".Final");
        }
        for (int i = 0; i <= 11; i++) {
            runJava8("5.1." + i + ".Final");
        }
        for (int i = 0; i <= 12; i++) {
            runJava8("5.2." + i + ".Final");
        }
    }

    private static void run(String version, String annotationsVersion, String... profiles)
            throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hibernate.version", version);
        if (!annotationsVersion.isEmpty()) {
            Util.updateLibVersion(MODULE_PATH, "hibernate.annotations.version", annotationsVersion);
        }
        Util.runTests(MODULE_PATH, profiles, JAVA6, JAVA7, JAVA8);
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hibernate.version", version);
        Util.runTests(MODULE_PATH, JAVA6, JAVA7, JAVA8);
    }

    private static void runJava7(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hibernate.version", version);
        Util.runTests(MODULE_PATH, JAVA7, JAVA8);
    }

    private static void runJava8(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hibernate.version", version);
        Util.runTests(MODULE_PATH, JAVA8);
    }
}
