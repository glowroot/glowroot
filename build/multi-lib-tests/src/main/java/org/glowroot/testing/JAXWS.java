/**
 * Copyright 2017 the original author or authors.
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

public class JAXWS {

    private static final String MODULE_PATH = "agent/plugins/jaxws-plugin";

    public static void main(String[] args) throws Exception {
        // see cxf-parent pom.xml for supported spring version range
        for (int i = 1; i <= 10; i++) {
            run2x("2.1." + i, "2.0.8");
        }
        for (int i = 1; i <= 12; i++) {
            run2x("2.2." + i, "2.5.6");
        }
        for (int i = 0; i <= 11; i++) {
            run2x("2.3." + i, "3.0.5.RELEASE");
        }
        for (int i = 0; i <= 10; i++) {
            run2x("2.4." + i, "3.0.6.RELEASE");
        }
        for (int i = 0; i <= 11; i++) {
            run2x("2.5." + i, "3.0.6.RELEASE");
        }
        for (int i = 0; i <= 17; i++) {
            run2x("2.6." + i, "3.2.18.RELEASE");
        }
        for (int i = 0; i <= 18; i++) {
            run2x("2.7." + i, "3.2.18.RELEASE");
        }
        for (int i = 0; i <= 15; i++) {
            runJava7("3.0." + i, "4.3.11.RELEASE");
        }
        for (int i = 0; i <= 13; i++) {
            runJava7("3.1." + i, "4.3.11.RELEASE");
        }
        runJava8("3.2.0", "4.3.11.RELEASE");
        runJava8("3.2.1", "4.3.12.RELEASE");
    }

    private static void run2x(String cxfVersion, String springVersion) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "cxf.version", cxfVersion);
        Util.updateLibVersion(MODULE_PATH, "spring.version", springVersion);
        Util.runTests(MODULE_PATH, JAVA6, JAVA7, JAVA8);
    }

    private static void runJava7(String cxfVersion, String springVersion) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "cxf.version", cxfVersion);
        Util.updateLibVersion(MODULE_PATH, "spring.version", springVersion);
        Util.runTests(MODULE_PATH, JAVA7, JAVA8);
    }

    private static void runJava8(String cxfVersion, String springVersion) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "cxf.version", cxfVersion);
        Util.updateLibVersion(MODULE_PATH, "spring.version", springVersion);
        Util.runTests(MODULE_PATH, JAVA8);
    }
}
