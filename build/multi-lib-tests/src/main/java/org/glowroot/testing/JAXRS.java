/**
 * Copyright 2016-2017 the original author or authors.
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

public class JAXRS {

    private static final String MODULE_PATH = "agent/plugins/jaxrs-plugin";

    public static void main(String[] args) throws Exception {
        run("2.0");
        run("2.0.1");
        run("2.1");
        run("2.2");
        run("2.3");
        run("2.3.1");
        run("2.4");
        run("2.4.1");
        run("2.5");
        run("2.5.1");
        run("2.5.2");
        run("2.6");
        runJava7("2.7");
        runJava7("2.8");
        runJava7("2.9");
        runJava7("2.9.1");
        runJava7("2.10");
        for (int i = 1; i <= 4; i++) {
            runJava7("2.10." + i);
        }
        runJava7("2.11");
        runJava7("2.12");
        runJava7("2.13");
        runJava7("2.14");
        runJava7("2.15");
        runJava7("2.16");
        runJava7("2.17");
        runJava7("2.18");
        runJava7("2.19");
        runJava7("2.20");
        runJava7("2.21");
        runJava7("2.21.1");
        runJava7("2.22");
        runJava7("2.22.1");
        runJava7("2.22.2");
        runJava7("2.22.3");
        runJava7("2.22.4");
        runJava7("2.23");
        runJava7("2.23.1");
        runJava7("2.23.2");
        runJava7("2.24");
        runJava7("2.24.1");
        runJava7("2.25");
        runJava7("2.25.1");
        runJava8("2.26", "jersey-2.26");
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "jersey.version", version);
        Util.runTests(MODULE_PATH, JAVA6, JAVA7, JAVA8);
    }

    private static void runJava7(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "jersey.version", version);
        Util.runTests(MODULE_PATH, JAVA7, JAVA8);
    }

    private static void runJava8(String version, String profile) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "jersey.version", version);
        Util.runTests(MODULE_PATH, profile, JAVA8);
    }
}
