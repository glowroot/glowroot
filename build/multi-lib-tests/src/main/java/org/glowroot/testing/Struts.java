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

import java.io.IOException;

import static org.glowroot.testing.JavaVersion.JAVA6;
import static org.glowroot.testing.JavaVersion.JAVA7;
import static org.glowroot.testing.JavaVersion.JAVA8;

public class Struts {

    private static final String MODULE_PATH = "agent/plugins/struts-plugin";

    public static void main(String[] args) throws Exception {
        struts1();
        struts2();
    }

    private static void struts1() throws Exception {
        final String test = "StrutsOneIT";
        updateLibVersion("struts1.version", "1.3.5");
        run(test);
        updateLibVersion("struts1.version", "1.3.8");
        run(test);
        updateLibVersion("struts1.version", "1.3.9");
        run(test);
        updateLibVersion("struts1.version", "1.3.10");
        run(test);
    }

    private static void struts2() throws Exception {
        final String test = "StrutsTwoIT";
        updateLibVersion("struts2.version", "2.1.8");
        run(test);
        updateLibVersion("struts2.version", "2.1.8.1");
        run(test);
        updateLibVersion("struts2.version", "2.2.1");
        run(test);
        updateLibVersion("struts2.version", "2.2.1.1");
        run(test);
        updateLibVersion("struts2.version", "2.2.3");
        run(test);
        updateLibVersion("struts2.version", "2.2.3.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.1.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.1.2");
        run(test);
        updateLibVersion("struts2.version", "2.3.3");
        run(test);
        updateLibVersion("struts2.version", "2.3.4");
        run(test);
        updateLibVersion("struts2.version", "2.3.4.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.7");
        run(test);
        updateLibVersion("struts2.version", "2.3.8");
        run(test);
        updateLibVersion("struts2.version", "2.3.12");
        run(test);
        updateLibVersion("struts2.version", "2.3.14");
        run(test);
        updateLibVersion("struts2.version", "2.3.14.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.14.2");
        run(test);
        updateLibVersion("struts2.version", "2.3.14.3");
        run(test);
        updateLibVersion("struts2.version", "2.3.15");
        run(test);
        updateLibVersion("struts2.version", "2.3.15.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.15.2");
        run(test);
        updateLibVersion("struts2.version", "2.3.15.3");
        run(test);
        updateLibVersion("struts2.version", "2.3.16");
        run(test);
        updateLibVersion("struts2.version", "2.3.16.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.16.2");
        run(test);
        updateLibVersion("struts2.version", "2.3.16.3");
        run(test);
        updateLibVersion("struts2.version", "2.3.20");
        run(test);
        updateLibVersion("struts2.version", "2.3.20.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.20.3");
        run(test);
        updateLibVersion("struts2.version", "2.3.24");
        run(test);
        updateLibVersion("struts2.version", "2.3.24.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.24.3");
        run(test);
        updateLibVersion("struts2.version", "2.3.28");
        run(test);
        updateLibVersion("struts2.version", "2.3.28.1");
        run(test);
        updateLibVersion("struts2.version", "2.3.29");
        run(test);
        updateLibVersion("struts2.version", "2.3.30");
        run(test);
        updateLibVersion("struts2.version", "2.3.31");
        run(test);
        updateLibVersion("struts2.version", "2.3.32");
        run(test);
        updateLibVersion("struts2.version", "2.3.33");
        run(test);
        updateLibVersion("struts2.version", "2.3.34");
        run(test);
        updateLibVersion("struts2.version", "2.5");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.1");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.2");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.5");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.8");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.10");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.10.1");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.12");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.13");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.14");
        runJava7(test);
        updateLibVersion("struts2.version", "2.5.14.1");
        runJava7(test);
    }

    private static void updateLibVersion(String property, String version) throws IOException {
        Util.updateLibVersion(MODULE_PATH, property, version);
    }

    private static void run(String test) throws Exception {
        Util.runTest(MODULE_PATH, test, JAVA6, JAVA7, JAVA8);
    }

    private static void runJava7(String test) throws Exception {
        Util.runTest(MODULE_PATH, test, JAVA7, JAVA8);
    }
}
