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

public class Spring {

    private static final String MODULE_PATH = "agent-parent/plugins/spring-plugin";

    public static void main(String[] args) throws Exception {
        for (int i = 0; i <= 7; i++) {
            run("3.0." + i + ".RELEASE", "spring-3.x");
        }
        for (int i = 0; i <= 4; i++) {
            run("3.1." + i + ".RELEASE", "spring-3.x");
        }
        for (int i = 0; i <= 16; i++) {
            run("3.2." + i + ".RELEASE", "spring-3.2.x");
        }
        for (int i = 0; i <= 9; i++) {
            run("4.0." + i + ".RELEASE");
        }
        for (int i = 0; i <= 9; i++) {
            run("4.1." + i + ".RELEASE");
        }
        for (int i = 0; i <= 5; i++) {
            run("4.2." + i + ".RELEASE");
        }
    }

    private static void run(String version, String... profiles) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "spring.version", version);
        Util.runTests(MODULE_PATH, profiles);
    }
}
