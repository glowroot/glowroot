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

import static org.glowroot.testing.JavaVersion.JAVA7;
import static org.glowroot.testing.JavaVersion.JAVA8;

public class Grails {

    private static final String MODULE_PATH = "agent/plugins/grails-plugin";

    public static void main(String[] args) throws Exception {
        for (int i = 6; i <= 17; i++) {
            run("3.0." + i);
        }
        for (int i = 0; i <= 16; i++) {
            run("3.1." + i);
        }
        for (int i = 0; i <= 13; i++) {
            run("3.2." + i);
        }
        for (int i = 0; i <= 8; i++) {
            run("3.3." + i);
        }
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "grails.version", version);
        Util.runTests(MODULE_PATH, JAVA8, JAVA7);
    }
}
