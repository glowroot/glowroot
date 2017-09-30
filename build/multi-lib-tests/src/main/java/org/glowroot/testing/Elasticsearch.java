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

import static org.glowroot.testing.JavaVersion.JAVA7;
import static org.glowroot.testing.JavaVersion.JAVA8;

public class Elasticsearch {

    private static final String MODULE_PATH = "agent/plugins/elasticsearch-plugin";

    public static void main(String[] args) throws Exception {
        run2x("2.0.0");
        run2x("2.0.1");
        run2x("2.0.2");
        run2x("2.1.0");
        run2x("2.1.1");
        run2x("2.1.2");
        run2x("2.2.0");
        run2x("2.2.1");
        run2x("2.2.2");
        for (int i = 0; i <= 5; i++) {
            run2x("2.3." + i);
        }
        for (int i = 0; i <= 5; i++) {
            run2x("2.4." + i);
        }

        // for log4j version, see e.g.
        // https://www.elastic.co/guide/en/elasticsearch/client/java-api/5.6/_log4j_2_logger.html
        run5x("5.0.0", "4.1.5.Final", "2.7");
        run5x("5.0.1", "4.1.5.Final", "2.7");
        run5x("5.0.2", "4.1.5.Final", "2.7");
        // there is no 5.1.0 in maven central
        run5x("5.1.1", "4.1.6.Final", "2.7");
        run5x("5.1.2", "4.1.6.Final", "2.7");
        run5x("5.2.0", "4.1.7.Final", "2.7");
        run5x("5.2.1", "4.1.7.Final", "2.7");
        run5x("5.2.2", "4.1.7.Final", "2.7");
        run5x("5.3.0", "4.1.7.Final", "2.7");
        run5x("5.3.1", "4.1.7.Final", "2.7");
        run5x("5.3.2", "4.1.7.Final", "2.7");
        run5x("5.3.3", "4.1.7.Final", "2.7");
        run5x("5.4.0", "4.1.9.Final", "2.8.2");
        run5x("5.4.1", "4.1.11.Final", "2.8.2");
        run5x("5.4.2", "4.1.11.Final", "2.8.2");
        run5x("5.4.3", "4.1.11.Final", "2.8.2");
        run5x("5.5.0", "4.1.11.Final", "2.8.2");
        run5x("5.5.1", "4.1.11.Final", "2.8.2");
        run5x("5.5.2", "4.1.11.Final", "2.8.2");
        run5x("5.5.3", "4.1.11.Final", "2.8.2");
        run5x("5.6.0", "4.1.13.Final", "2.9.0");
        run5x("5.6.1", "4.1.13.Final", "2.9.0");
        run5x("5.6.2", "4.1.13.Final", "2.9.0");
    }

    private static void run2x(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "elasticsearch.version", version);
        Util.runTests(MODULE_PATH, "elasticsearch-2.x", JAVA7, JAVA8);
    }

    private static void run5x(String version, String nettyVersion, String log4jVersion)
            throws Exception {
        Util.updateLibVersion(MODULE_PATH, "elasticsearch.version", version);
        Util.updateLibVersion(MODULE_PATH, "netty.version", nettyVersion);
        Util.updateLibVersion(MODULE_PATH, "log4j.version", log4jVersion);
        Util.runTests(MODULE_PATH, "elasticsearch-5.x", JAVA8);
    }
}
