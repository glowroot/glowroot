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

public class JSF {

    private static final String MODULE_PATH = "agent/plugins/jsf-plugin";

    public static void main(String[] args) throws Exception {
        for (int i = 7; i <= 11; i++) {
            run("2.0." + i);
        }
        run("2.0.9-02");
        run("2.0.9-05");
        run("2.0.9-08");
        for (int i = 1; i <= 3; i++) {
            run("2.0.11-" + String.format("%02d", i));
        }
        for (int i = 2; i <= 29; i++) {
            run("2.1." + i);
        }
        run("2.1.5-02");
        run("2.1.5-04");
        for (int i = 1; i <= 10; i++) {
            run("2.1.7-" + String.format("%02d", i));
        }
        for (int i = 2; i <= 16; i++) {
            run("2.1.20-" + String.format("%02d", i));
        }
        for (int i = 1; i <= 11; i++) {
            run("2.1.29-" + String.format("%02d", i));
        }
        for (int i = 0; i <= 18; i++) {
            run("2.2." + i);
        }
        for (int i = 1; i <= 30; i++) {
            run("2.2.8-" + String.format("%02d", i));
        }
        for (int i = 0; i <= 7; i++) {
            runJava8("2.3." + i);
        }
        runJava8("2.3.3.99");
        runJava8("2.3.3.100");
        runJava8("2.3.3.101");
        runJava8("2.3.3.102");
        runJava8("2.4.0");
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "faces.version", version);
        Util.runTests(MODULE_PATH, JAVA6, JAVA7, JAVA8);
    }

    private static void runJava8(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "faces.version", version);
        Util.runTests(MODULE_PATH, JAVA8);
    }
}
