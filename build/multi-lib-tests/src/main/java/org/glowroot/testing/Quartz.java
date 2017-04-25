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

public class Quartz {

    private static final String MODULE_PATH = "agent/plugins/quartz-plugin";

    public static void main(String[] args) throws Exception {
        run("1.7.2", "quartz-old");
        run("1.7.3", "quartz-old");
        for (int i = 0; i <= 6; i++) {
            run("1.8." + i, "quartz-old");
        }
        run("2.0.0");
        run("2.0.1");
        run("2.0.2");
        for (int i = 0; i <= 7; i++) {
            run("2.1." + i);
        }
        run("2.2.0");
        run("2.2.1");
        run("2.2.2");
        run("2.2.3");
        runJava7("2.3.0");
    }

    private static void run(String version, String... profiles) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "quartz.version", version);
        Util.runTests(MODULE_PATH, profiles, JAVA6, JAVA7, JAVA8);
    }

    private static void runJava7(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "quartz.version", version);
        Util.runTests(MODULE_PATH, JAVA7, JAVA8);
    }
}
