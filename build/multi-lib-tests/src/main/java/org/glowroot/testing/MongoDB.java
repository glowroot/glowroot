/**
 * Copyright 2018 the original author or authors.
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

import static org.glowroot.testing.JavaVersion.JAVA8;

public class MongoDB {

    private static final String MODULE_PATH = "agent/plugins/mongodb-plugin";

    public static void main(String[] args) throws Exception {
        run("0.11");
        run("1.0");
        run("1.1");
        run("1.2");
        run("1.2.1");
        run("1.3");
        run("1.4");
        run("2.0");
        run("2.1");
        run("2.2");
        run("2.3");
        run("2.4");
        run("2.5");
        run("2.5.1");
        run("2.5.2");
        run("2.5.3");
        run("2.6");
        run("2.6.1");
        run("2.6.2");
        run("2.6.3");
        run("2.6.5");
        run("2.7.0");
        run("2.7.1");
        run("2.7.2");
        run("2.7.3");
        run("2.8.0");
        run("2.9.0");
        run("2.9.1");
        run("2.9.2");
        run("2.9.3");
        run("2.10.0");
        run("2.10.1");
        run("2.11.0");
        run("2.11.1");
        run("2.11.2");
        run("2.11.3");
        run("2.11.4");
        run("2.12.0");
        run("2.12.1");
        run("2.12.2");
        run("2.12.3");
        run("2.12.4");
        run("2.12.5");
        run("2.13.0");
        run("2.13.1");
        run("2.13.2");
        run("2.13.3");
        run("2.14.0");
        run("2.14.1");
        run("2.14.2");
        run("2.14.3");

        run("3.0.0");
        run("3.0.1");
        run("3.0.2");
        run("3.0.3");
        run("3.0.4");
        run("3.1.0");
        run("3.1.1");
        run("3.2.0");
        run("3.2.1");
        run("3.2.2");
        run("3.3.0");
        run("3.4.0");
        run("3.4.1");
        run("3.4.2");
        run("3.4.3");
        run("3.5.0");
        run("3.6.0");
        run("3.6.1");
        run("3.6.2");
        run("3.6.3");
        run("3.6.4");
        run("3.7.0");
        run("3.7.1");
        run("3.8.0");
        run("3.8.1");
        run("3.8.2");
    }

    private static void run(String version) throws Exception {
        String profile = version.matches("[012]\\..*") || version.matches("3\\.[0-6]\\..*")
                ? "mongodb-pre-3.7.x"
                : "mongodb-3.7.x";
        Util.updateLibVersion(MODULE_PATH, "mongodb.driver.version", version);
        // testcontainers requires Java 8+
        Util.runTests(MODULE_PATH, profile, JAVA8);
    }
}
