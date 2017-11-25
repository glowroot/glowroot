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

public class Logger {

    private static final String MODULE_PATH = "agent/plugins/logger-plugin";

    public static void main(String[] args) throws Exception {
        logback();
        log4j();
        log4j2x();
    }

    private static void logback() throws Exception {
        final String test = "LogbackIT,LogbackMarkerIT";

        updateLibVersion("logback.version", "0.9");
        updateLibVersion("slf4j.version", "1.2");
        run(test, "logback-old");

        for (int i = 1; i <= 5; i++) {
            updateLibVersion("logback.version", "0.9." + i);
            updateLibVersion("slf4j.version", "1.3.0");
            run(test, "logback-old");
        }

        updateLibVersion("logback.version", "0.9.6");
        updateLibVersion("slf4j.version", "1.4.0");
        run(test, "logback-old");

        updateLibVersion("logback.version", "0.9.7");
        updateLibVersion("slf4j.version", "1.4.0");
        run(test, "logback-old");

        updateLibVersion("logback.version", "0.9.8");
        updateLibVersion("slf4j.version", "1.4.3");
        run(test, "logback-old");

        updateLibVersion("logback.version", "0.9.9");
        updateLibVersion("slf4j.version", "1.5.0");
        run(test, "logback-old");

        // logback 0.9.10 doesn't work period in multi-threaded environments due to
        // https://github.com/qos-ch/logback/commit/d699a4afd4cad728ab2aa57b04ef357e15d8c8cf

        updateLibVersion("logback.version", "0.9.11");
        updateLibVersion("slf4j.version", "1.5.5");
        run(test, "logback-old");

        for (int i = 12; i <= 15; i++) {
            updateLibVersion("logback.version", "0.9." + i);
            updateLibVersion("slf4j.version", "1.5.6");
            run(test, "logback-old");
        }

        updateLibVersion("logback.version", "0.9.16");
        updateLibVersion("slf4j.version", "1.5.8");
        run(test, "logback-old");

        updateLibVersion("logback.version", "0.9.17");
        updateLibVersion("slf4j.version", "1.5.8");
        run(test, "logback-old");

        updateLibVersion("logback.version", "0.9.18");
        updateLibVersion("slf4j.version", "1.5.10");
        run(test, "logback-old");

        updateLibVersion("logback.version", "0.9.19");
        updateLibVersion("slf4j.version", "1.5.11");
        run(test, "logback-old");

        updateLibVersion("logback.version", "0.9.20");
        updateLibVersion("slf4j.version", "1.5.11");
        run(test, "logback-old");

        for (int i = 21; i <= 25; i++) {
            updateLibVersion("logback.version", "0.9." + i);
            updateLibVersion("slf4j.version", "1.6.0");
            run(test);
        }

        for (int i = 26; i <= 29; i++) {
            updateLibVersion("logback.version", "0.9." + i);
            updateLibVersion("slf4j.version", "1.6.1");
            run(test);
        }

        updateLibVersion("logback.version", "0.9.30");
        updateLibVersion("slf4j.version", "1.6.2");
        run(test);

        for (int i = 0; i <= 13; i++) {
            updateLibVersion("slf4j.version", "1.7.19");
            updateLibVersion("logback.version", "1.0." + i);
            run(test);
        }
        for (int i = 0; i <= 8; i++) {
            updateLibVersion("slf4j.version", "1.7.21");
            updateLibVersion("logback.version", "1.1." + i);
            run(test);
        }
        for (int i = 9; i <= 10; i++) {
            updateLibVersion("slf4j.version", "1.7.22");
            updateLibVersion("logback.version", "1.1." + i);
            run(test);
        }
        for (int i = 0; i <= 1; i++) {
            updateLibVersion("slf4j.version", "1.7.22");
            updateLibVersion("logback.version", "1.2." + i);
            run(test);
        }
        for (int i = 2; i <= 3; i++) {
            updateLibVersion("slf4j.version", "1.7.25");
            updateLibVersion("logback.version", "1.2.2");
            run(test);
        }
    }

    private static void log4j() throws Exception {
        final String test = "Log4jIT";
        for (int i = 4; i <= 17; i++) {
            if (i == 10) {
                // there is no 1.2.10 in maven central
                continue;
            }
            updateLibVersion("log4j.version", "1.2." + i);
            run(test);
        }
    }

    private static void log4j2x() throws Exception {
        final String test = "Log4j2xIT,Log4j2xMarkerIT";
        updateLibVersion("log4j2x.version", "2.0");
        run(test);
        updateLibVersion("log4j2x.version", "2.0.1");
        run(test);
        updateLibVersion("log4j2x.version", "2.0.2");
        run(test);
        updateLibVersion("log4j2x.version", "2.1");
        run(test);
        updateLibVersion("log4j2x.version", "2.2");
        run(test);
        updateLibVersion("log4j2x.version", "2.3");
        run(test);
        updateLibVersion("log4j2x.version", "2.4");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.4.1");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.5");
        runJava7(test);
        // tests fail with log4j 2.6 due to https://github.com/apache/logging-log4j2/pull/31
        updateLibVersion("log4j2x.version", "2.6.1");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.6.2");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.7");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.8");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.8.1");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.8.2");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.9.0");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.9.1");
        runJava7(test);
        updateLibVersion("log4j2x.version", "2.10.0");
        runJava7(test);
    }

    private static void updateLibVersion(String property, String version) throws IOException {
        Util.updateLibVersion(MODULE_PATH, property, version);
    }

    private static void run(String test) throws Exception {
        Util.runTest(MODULE_PATH, test, JAVA6, JAVA7, JAVA8);
    }

    private static void run(String test, String profile) throws Exception {
        Util.runTest(MODULE_PATH, test, profile, JAVA6, JAVA7, JAVA8);
    }

    private static void runJava7(String test) throws Exception {
        Util.runTest(MODULE_PATH, test, JAVA7, JAVA8);
    }
}
