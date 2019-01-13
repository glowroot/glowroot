/**
 * Copyright 2018-2019 the original author or authors.
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

import static org.glowroot.testing.JavaVersion.JAVA7;
import static org.glowroot.testing.JavaVersion.JAVA8;

public class Kafka {

    private static final String MODULE_PATH = "agent/plugins/kafka-plugin";

    public static void main(String[] args) throws Exception {
        runJava7("0.9.0.0");
        runJava7("0.9.0.1");
        runJava7("0.10.0.0");
        runJava7("0.10.0.1");
        runJava7("0.10.1.0");
        runJava7("0.10.1.1");
        runJava7("0.10.2.0");
        runJava7("0.10.2.1");
        runJava7("0.10.2.2");
        runJava7("0.11.0.0");
        runJava7("0.11.0.1");
        runJava7("0.11.0.2");
        runJava7("0.11.0.3");
        runJava7("1.0.0");
        runJava7("1.0.1");
        runJava7("1.1.0");
        runJava7("1.1.1");
        runJava8("2.0.0");
        runJava8("2.0.1");
        runJava8("2.1.0");
    }

    private static void runJava7(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "kafka.version", version);
        Util.runTests(MODULE_PATH, JAVA8, JAVA7);
    }

    private static void runJava8(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "kafka.version", version);
        Util.runTests(MODULE_PATH, JAVA8);
    }
}
