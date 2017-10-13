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

public class JSF {

    private static final String MODULE_PATH = "agent/plugins/jsf-plugin";

    public static void main(String[] args) throws Exception {
        // 2.0.0 fails to deploy on tomcat (https://github.com/javaserverfaces/mojarra/issues/1377)
        for (int i = 1; i <= 11; i++) {
            if (i == 4 || i == 5) {
                // there is no 2.0.4 or 2.0.5 in maven central
                continue;
            }
            run("2.0." + i);
        }
        // 2.1.0 fails to deploy on tomcat (https://github.com/javaserverfaces/mojarra/issues/1941)
        for (int i = 1; i <= 29; i++) {
            if (i == 1) {
                // there is no 2.1.1 in maven central
                continue;
            }
            run("2.1." + i);
        }
        run("2.0.4-12");
        run("2.0.4-13");
        run("2.0.9-02");
        run("2.0.9-03");
        run("2.0.9-04");
        run("2.0.9-05");
        run("2.0.11-01");
        run("2.0.11-02");
        run("2.0.11-03");
        run("2.1.3_01");
        run("2.1.5-02");
        run("2.1.5-03");
        run("2.1.5-04");
        run("2.1.7-02");
        run("2.1.7-03");
        run("2.1.7-04");
        run("2.1.7-05");
        run("2.1.7-06");
        run("2.1.7-07");
        run("2.1.20-04");
        run("2.1.20-05");
        run("2.1.20-06");
        run("2.1.20-07");
        run("2.1.20-08");
        run("2.1.20-09");
        run("2.1.20-10");
        run("2.1.20-11");
        run("2.1.20-12");
        run("2.1.29-01");
        run("2.1.29-02");
        run("2.1.29-03");
        run("2.1.29-04");
        run("2.1.29-05");
        run("2.1.29-06");
        run("2.1.29-07");
        run("2.2.8-01");
        run("2.2.8-02");
        run("2.2.8-04");
        run("2.2.8-05");
        run("2.2.8-06");
        run("2.2.8-07");
        run("2.2.8-08");
        run("2.2.8-09");
        run("2.2.8-10");
        run("2.2.8-11");
        run("2.2.8-12");
        run("2.2.8-13");
        run("2.2.8-14");
        run("2.2.8-15");
        run("2.2.8-16");
        run("2.2.9");
        run("2.2.10");
        run("2.2.11");
        run("2.2.12");
        run("2.2.13");
        run("2.2.14");
        run("2.2.15");
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "faces.version", version);
        Util.runTests(MODULE_PATH, JAVA6, JAVA7, JAVA8);
    }
}
