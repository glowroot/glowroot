/**
 * Copyright 2016-2019 the original author or authors.
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

public class Netty {

    private static final String MODULE_PATH = "agent/plugins/netty-plugin";

    public static void main(String[] args) throws Exception {
        netty();
        vertx();
    }

    private static void netty() throws Exception {
        runNetty("3.3.0.Final", "netty-3.x");
        runNetty("3.3.1.Final", "netty-3.x");
        for (int i = 0; i <= 6; i++) {
            runNetty("3.4." + i + ".Final", "netty-3.x");
        }
        for (int i = 0; i <= 13; i++) {
            runNetty("3.5." + i + ".Final", "netty-3.x");
        }
        for (int i = 0; i <= 10; i++) {
            runNetty("3.6." + i + ".Final", "netty-3.x");
        }
        runNetty("3.7.0.Final", "netty-3.x");
        runNetty("3.7.1.Final", "netty-3.x");
        for (int i = 0; i <= 3; i++) {
            runNetty("3.8." + i + ".Final", "netty-3.x");
        }
        for (int i = 0; i <= 9; i++) {
            runNetty("3.9." + i + ".Final", "netty-3.x");
        }
        for (int i = 0; i <= 6; i++) {
            runNetty("3.10." + i + ".Final", "netty-3.x");
        }
        for (int i = 0; i <= 56; i++) {
            runNetty("4.0." + i + ".Final", "netty-4.x");
        }
        for (int i = 1; i <= 7; i++) {
            runNetty("4.1.0.CR" + i, "netty-4.x");
        }
        for (int i = 0; i <= 32; i++) {
            if (i == 28) {
                // Netty 4.1.28 fails on Java 6, see https://github.com/netty/netty/issues/8166
                runNettyJava7("4.1." + i + ".Final", "netty-4.x");
            } else {
                runNetty("4.1." + i + ".Final", "netty-4.x");
            }
            runNettyJava7("4.1." + i + ".Final", "netty-http2-4.1.x");
        }
    }

    private static void vertx() throws Exception {
        runVertx("1.2.1.final", "vertx-1.x");
        runVertx("1.2.3.final", "vertx-1.x");
        runVertx("1.3.0.final", "vertx-1.x");
        runVertx("1.3.1.final", "vertx-1.x");

        runVertx("2.0.0-final", "vertx-2.x");
        runVertx("2.0.1-final", "vertx-2.x");
        runVertx("2.0.2-final", "vertx-2.x");

        runVertx("2.1", "vertx-2.x");
        runVertx("2.1.1", "vertx-2.x");
        runVertx("2.1.2", "vertx-2.x");
        runVertx("2.1.3", "vertx-2.x");
        runVertx("2.1.4", "vertx-2.x");
        runVertx("2.1.5", "vertx-2.x");
        runVertx("2.1.6", "vertx-2.x");

        runVertxJava8("3.0.0", "vertx-3.x");
        runVertxJava8("3.1.0", "vertx-3.x");
        runVertxJava8("3.2.0", "vertx-3.x");
        runVertxJava8("3.2.1", "vertx-3.x");
        runVertxJava8("3.3.0", "vertx-3.x");
        runVertxJava8("3.3.1", "vertx-3.x");
        runVertxJava8("3.3.2", "vertx-3.x");
        runVertxJava8("3.3.3", "vertx-3.x");
        runVertxJava8("3.4.0", "vertx-3.x");
        runVertxJava8("3.4.1", "vertx-3.x");
        runVertxJava8("3.4.2", "vertx-3.x");
        runVertxJava8("3.5.0", "vertx-3.x");
        runVertxJava8("3.5.1", "vertx-3.x");
        runVertxJava8("3.5.2", "vertx-3.x");
        runVertxJava8("3.5.3", "vertx-3.x");
        runVertxJava8("3.5.4", "vertx-3.x");
        runVertxJava8("3.6.0", "vertx-3.x");
        runVertxJava8("3.6.1", "vertx-3.x");
        runVertxJava8("3.6.2", "vertx-3.x");
    }

    private static void runNetty(String version, String... profile) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "netty.version", version);
        Util.runTests(MODULE_PATH, profile, JAVA8, JAVA7, JAVA6);
    }

    private static void runNettyJava7(String version, String... profile) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "netty.version", version);
        Util.runTests(MODULE_PATH, profile, JAVA8, JAVA7);
    }

    private static void runVertx(String vertxVersion, String profile)
            throws Exception {
        Util.updateLibVersion(MODULE_PATH, "vertx.version", vertxVersion);
        Util.runTests(MODULE_PATH, profile, JAVA8, JAVA7);
    }

    private static void runVertxJava8(String vertxVersion, String profile)
            throws Exception {
        Util.updateLibVersion(MODULE_PATH, "vertx.version", vertxVersion);
        Util.runTests(MODULE_PATH, profile, JAVA8);
    }
}
