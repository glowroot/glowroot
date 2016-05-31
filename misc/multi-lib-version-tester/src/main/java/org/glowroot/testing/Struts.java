/**
 * Copyright 2016 the original author or authors.
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

public class Struts {

    private static final String MODULE_PATH = "agent-parent/plugins/struts-plugin";

    public static void main(String[] args) throws Exception {
        struts1();
        struts2();
    }

    private static void struts1() throws Exception {
        final String test = "StrutsOneIT";
        updateLibVersion("struts1.version", "1.3.5");
        runTest(test);
        updateLibVersion("struts1.version", "1.3.8");
        runTest(test);
        updateLibVersion("struts1.version", "1.3.9");
        runTest(test);
        updateLibVersion("struts1.version", "1.3.10");
        runTest(test);
    }

    private static void struts2() throws Exception {
        final String test = "StrutsTwoIT";
        updateLibVersion("struts2.version", "2.1.8");
        runTest(test);
        updateLibVersion("struts2.version", "2.1.8.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.2.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.2.1.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.2.3");
        runTest(test);
        updateLibVersion("struts2.version", "2.2.3.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.1.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.1.2");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.3");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.4");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.4.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.7");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.8");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.12");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.14");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.14.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.14.2");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.14.3");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.15");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.15.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.15.2");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.15.3");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.16");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.16.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.16.2");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.16.3");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.20");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.20.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.20.3");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.24");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.24.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.24.3");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.28");
        runTest(test);
        updateLibVersion("struts2.version", "2.3.28.1");
        runTest(test);
        updateLibVersion("struts2.version", "2.5");
        runTest(test);
    }

    private static void updateLibVersion(String property, String version) throws IOException {
        Util.updateLibVersion(MODULE_PATH, property, version);
    }

    private static void runTest(String test) throws Exception {
        Util.runTest(MODULE_PATH, test);
    }
}
