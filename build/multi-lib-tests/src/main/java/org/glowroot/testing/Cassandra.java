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

public class Cassandra {

    private static final String MODULE_PATH = "agent/plugins/cassandra-plugin";

    public static void main(String[] args) throws Exception {
        for (int i = 4; i <= 12; i++) {
            run("2.0." + i);
        }
        run("2.0.9.1");
        run("2.0.9.2");
        run("2.0.12.1");
        run("2.0.12.2");
        run("2.0.12.3");
        for (int i = 0; i <= 10; i++) {
            run("2.1." + i);
        }
        run("2.1.7.1");
        run("2.1.10.1");
        run("2.1.10.2");
        run("2.1.10.3");
        run("3.0.0");
        run("3.0.1");
        run("3.0.2");
        run("3.0.3");
        run("3.0.4");
        run("3.0.5");
        run("3.0.6");
        run("3.1.0");
        run("3.1.1");
        run("3.1.2");
        run("3.1.3");
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "datastax.driver.version", version);
        Util.runTests(MODULE_PATH, JAVA6, JAVA7, JAVA8);
    }
}
