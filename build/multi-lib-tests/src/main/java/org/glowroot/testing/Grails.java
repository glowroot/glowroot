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

import static org.glowroot.testing.JavaVersion.JAVA7;
import static org.glowroot.testing.JavaVersion.JAVA8;

public class Grails {

    private static final String MODULE_PATH = "agent/plugins/grails-plugin";

    public static void main(String[] args) throws Exception {
        for (int i = 6; i <= 17; i++) {
            if (i == 7 || i == 8) {
                // there is no 3.0.7 or 3.0.8 in maven central
                continue;
            }
            run("3.0." + i);
        }
        for (int i = 0; i <= 16; i++) {
            if (i == 1 || i == 8 || i == 14) {
                // there is no 3.1.1, 3.1.8 or 3.1.14 in maven central
                continue;
            }
            if (i == 15) {
                // grails-web-url-mappings 3.1.15 (in maven central) depends on
                // grails-datastore-core 5.0.13.BUILD-SNAPSHOT (which is not in maven central)
                continue;
            }
            run("3.1." + i);
        }
        run("3.2.0");
        run("3.2.1");
        for (int i = 4; i <= 11; i++) {
            run("3.2." + i);
        }
        run("3.3.0");
        run("3.3.1");
        run("3.3.2");
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "grails.version", version);
        Util.runTests(MODULE_PATH, JAVA7, JAVA8);
    }
}
