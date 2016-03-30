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

public class Play {

    private static String MODULE_PATH = "agent-parent/plugins/play-plugin";

    public static void main(String[] args) throws Exception {
        run("1.1");
        run("1.1.1");
        run("1.1.2");
        run("1.2");
        run("1.2.1");
        run("1.2.1.1");
        run("1.2.2");
        run("1.2.3");
        run("1.2.4");
        run("1.2.5");
        run("1.2.5.1");
        run("1.2.5.2");
        run("1.2.5.3");
        run("1.2.5.5");
        run("1.2.5.6");
        run("1.2.6");
        run("1.2.6.1");
        run("1.2.6.2");
        run("1.2.7");
        run("1.2.7.2");
        run("1.3.0");
        run("1.3.1");
        run("1.3.2");
        run("1.3.3");
        run("1.3.4");
        run("1.4.0");
        run("1.4.1");
        run("1.4.2");
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "play.version", version);
        Util.runTests(MODULE_PATH);
    }
}
