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

public class JAXRS {

    private static String MODULE_PATH = "agent-parent/plugins/jaxrs-plugin";

    public static void main(String[] args) throws Exception {
        // run("2.0");
        // run("2.0.1");
        // run("2.1");
        // run("2.2");
        // run("2.3");
        // run("2.3.1");
        // run("2.4");
        // run("2.4.1");
        // run("2.5");
        // run("2.5.1");
        // run("2.5.2");
        // run("2.6");
        // run("2.7");
        // run("2.8");
        // run("2.9");
        // run("2.9.1");
        // run("2.10");
        // for (int i = 1; i <= 4; i++) {
        // run("2.10." + i);
        // }
        // run("2.11");
        // run("2.12");
        // run("2.13");
        // run("2.14");
        // run("2.15");
        // run("2.16");
        // run("2.17");
        // run("2.18");
        // run("2.19");
        run("2.20");
        run("2.21");
        run("2.21.1");
        run("2.22");
        run("2.22.1");
        run("2.22.2");
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "jersey.version", version);
        Util.runTests(MODULE_PATH);
    }
}
