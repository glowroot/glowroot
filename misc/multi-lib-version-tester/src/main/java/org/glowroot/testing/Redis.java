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

import static org.glowroot.testing.JavaVersion.JAVA6;
import static org.glowroot.testing.JavaVersion.JAVA7;
import static org.glowroot.testing.JavaVersion.JAVA8;

public class Redis {

    private static final String MODULE_PATH = "agent/plugins/redis-plugin";

    public static void main(String[] args) throws Exception {
        run("2.0.0");
        run("2.1.0");
        run("2.2.0");
        run("2.2.1");
        run("2.3.0");
        run("2.3.1");
        run("2.4.0");
        run("2.4.1");
        run("2.4.2");
        run("2.5.0");
        run("2.5.1");
        run("2.5.2");
        run("2.6.0");
        run("2.6.1");
        run("2.6.2");
        run("2.6.3");
        run("2.7.0");
        run("2.7.1");
        run("2.7.2");
        run("2.7.3");
        run("2.8.0");
        run("2.8.1");
        run("2.8.2");
        run("2.9.0");
    }

    private static void run(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "jedis.version", version);
        Util.runTests(MODULE_PATH, JAVA6, JAVA7, JAVA8);
    }
}
