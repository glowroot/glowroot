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

public class JDBC {

    private static final String MODULE_PATH = "agent/plugins/jdbc-plugin";

    public static void main(String[] args) throws Exception {
        hsqldb();
        h2();
        commonsDbcpWrapped();
        commonsDbcp2Wrapped();
        tomcatWrapped();
        glassfishWrapped();
        hikariCpWrapped();
        bitronixWrapped();
    }

    private static void hsqldb() throws Exception {
        runHsqldb("2.0.0");
        runHsqldb("2.2.4");
        for (int i = 6; i <= 9; i++) {
            runHsqldb("2.2." + i);
        }
        for (int i = 0; i <= 6; i++) {
            runHsqldb("2.3." + i);
        }
        runHsqldbJava8("2.4.0");
        runHsqldbJava8("2.4.1");
        // return pom.xml file to version 2.3.6 since other tests use hsqldb driver (the wrapper
        // tests, and also ConnectionAndTxLifecycleIT tests) and hsqldb 2.4.0+ requires Java 8+
        Util.updateLibVersion(MODULE_PATH, "hsqldb.version", "2.3.6");
    }

    private static void h2() throws Exception {
        runH2("1.0.20061217");
        runH2("1.0.20070304");
        runH2("1.0.20070429");
        runH2("1.0.20070617");
        for (int i = 57; i <= 79; i++) {
            runH2("1.0." + i);
        }
        for (int i = 100; i <= 119; i++) {
            runH2("1.1." + i);
        }
        for (int i = 120; i <= 145; i++) {
            runH2("1.2." + i);
        }
        runH2("1.3.146");
        runH2("1.2.147");
        for (int i = 148; i <= 176; i++) {
            runH2("1.3." + i);
        }
        for (int i = 177; i <= 191; i++) {
            runH2("1.4." + i);
        }
        for (int i = 192; i <= 197; i++) {
            runH2Java7("1.4." + i);
        }
    }

    private static void commonsDbcpWrapped() throws Exception {
        runCommonsDbcpWrapped("1.3");
        runCommonsDbcpWrapped("1.4");
    }

    private static void commonsDbcp2Wrapped() throws Exception {
        runCommonsDbcp2Wrapped("2.0");
        runCommonsDbcp2Wrapped("2.0.1");
        runCommonsDbcp2Wrapped("2.1");
        runCommonsDbcp2Wrapped("2.1.1");
        runCommonsDbcp2Wrapped("2.2.0");
        runCommonsDbcp2Wrapped("2.3.0");
        runCommonsDbcp2Wrapped("2.4.0");
        runCommonsDbcp2Wrapped("2.5.0");
    }

    private static void tomcatWrapped() throws Exception {
        for (int i = 19; i <= 92; i++) {
            if (i == 24 || i == 31 || i == 36 || i == 38 || i == 43 || i == 44 || i == 45
                    || i == 46 || i == 48 || i == 49 || i == 51 || i == 58 || i == 60 || i == 66
                    || i == 71 || i == 74 || i == 80 || i == 83 || i == 87 || i == 89) {
                // not in maven central
                continue;
            }
            runTomcatWrapped("7.0." + i);
        }
        runTomcatWrappedJava7("8.0.1");
        runTomcatWrappedJava7("8.0.3");
        runTomcatWrappedJava7("8.0.5");
        runTomcatWrappedJava7("8.0.8");
        runTomcatWrappedJava7("8.0.9");
        runTomcatWrappedJava7("8.0.11");
        runTomcatWrappedJava7("8.0.12");
        runTomcatWrappedJava7("8.0.14");
        runTomcatWrappedJava7("8.0.15");
        runTomcatWrappedJava7("8.0.17");
        runTomcatWrappedJava7("8.0.18");
        for (int i = 20; i <= 53; i++) {
            if (i == 25 || i == 31 || i == 34 || i == 40) {
                // not in maven central
                continue;
            }
            runTomcatWrappedJava7("8.0." + i);
        }
        for (int i = 0; i <= 37; i++) {
            if (i == 1 || i == 7 || i == 10 || i == 17 || i == 18 || i == 22 || i == 25
                    || i == 26 || i == 36) {
                // not in maven central
                continue;
            }
            runTomcatWrappedJava7("8.5." + i);
        }
        for (int i = 1; i <= 14; i++) {
            if (i == 3 || i == 9) {
                // not in maven central
                continue;
            }
            runTomcatWrappedJava8("9.0." + i);
        }
    }

    private static void glassfishWrapped() throws Exception {
        runGlassfishWrappedJava7("4.0");
        runGlassfishWrappedJava7("4.1");
        runGlassfishWrappedJava7("4.1.1");
        runGlassfishWrappedJava7("4.1.2");
        runGlassfishWrappedJava8("5.0");
        runGlassfishWrappedJava8("5.0.1");
    }

    private static void hikariCpWrapped() throws Exception {
        for (int i = 4; i <= 9; i++) {
            runHikariCp1xWrapped("1.3." + i);
        }
        runHikariCp1xWrapped("1.4.0");
        // 2.0.0 had problems on Java 6, see https://github.com/brettwooldridge/HikariCP/issues/125
        runHikariCpJava6WrappedButNotJava6("2.0.0");
        runHikariCpJava8Wrapped("2.0.0");
        runHikariCpJava6Wrapped("2.0.1");
        runHikariCpJava8Wrapped("2.0.1");
        runHikariCpJava6Wrapped("2.1.0");
        runHikariCpJava8Wrapped("2.1.0");
        // 2.2.0 through 2.2.3 had issues with hsqldb driver,
        // see https://github.com/brettwooldridge/HikariCP/issues/191
        // 2.2.4 had problems on Java 6, see https://github.com/brettwooldridge/HikariCP/issues/188
        runHikariCpJava6WrappedButNotJava6("2.2.4");
        runHikariCpJava8Wrapped("2.2.4");
        runHikariCpJava6Wrapped("2.2.5");
        runHikariCpJava8Wrapped("2.2.5");
        for (int i = 0; i <= 13; i++) {
            if (i == 10 || i == 11) {
                // 2.3.10 and 2.3.11 -java6 artifact was compiled to Java 7 (51.0) bytecode
                runHikariCpJava6WrappedButNotJava6("2.3." + i);
            } else {
                runHikariCpJava6Wrapped("2.3." + i);
            }
            runHikariCpJava8Wrapped("2.3." + i);
        }
        for (int i = 0; i <= 7; i++) {
            runHikariCpJava8Wrapped("2.4." + i);
        }
        for (int i = 8; i <= 13; i++) {
            runHikariCpJava7Wrapped("2.4." + i);
        }
        runHikariCpJava8Wrapped("2.5.0");
        runHikariCpJava8Wrapped("2.5.1");
        runHikariCpJava8Wrapped("2.6.0");
        runHikariCpJava8Wrapped("2.6.1");
        runHikariCpJava8Wrapped("2.6.2");
        runHikariCpJava8Wrapped("2.6.3");
        for (int i = 0; i <= 8; i++) {
            runHikariCpJava8Wrapped("2.7." + i);
        }
        runHikariCpJava8Wrapped("3.0.0");
        runHikariCpJava8Wrapped("3.1.0");
        runHikariCpJava8Wrapped("3.2.0");
        runHikariCpJava8Wrapped("3.3.0");
    }

    private static void bitronixWrapped() throws Exception {
        runBitronix2xWrapped("1.2");
        runBitronix2xWrapped("1.3");
        runBitronix2xWrapped("1.3.1");
        runBitronix2xWrapped("1.3.2");
        runBitronix2xWrapped("1.3.3");
        runBitronix2xWrapped("2.0.0");
        runBitronix2xWrapped("2.0.1");
        runBitronix2xWrapped("2.1.0");
        runBitronix2xWrapped("2.1.1");
        runBitronix2xWrapped("2.1.2");
        runBitronix2xWrapped("2.1.3");
        runBitronix2xWrapped("2.1.4");
        runBitronix3xWrapped("3.0.0-mk1");
    }

    private static void runHsqldb(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hsqldb.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=HSQLDB", JAVA8, JAVA7,
                JAVA6);
    }

    private static void runHsqldbJava8(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hsqldb.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=HSQLDB", JAVA8);
    }

    private static void runH2(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "h2.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=H2", JAVA8, JAVA7, JAVA6);
    }

    private static void runH2Java7(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "h2.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=H2", JAVA8, JAVA7);
    }

    private static void runCommonsDbcpWrapped(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "commons.dbcp.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=COMMONS_DBCP_WRAPPED", JAVA8,
                JAVA7, JAVA6);
    }

    private static void runCommonsDbcp2Wrapped(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "commons.dbcp2.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=COMMONS_DBCP2_WRAPPED",
                JAVA8);
    }

    private static void runTomcatWrapped(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "tomcat.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=TOMCAT_JDBC_POOL_WRAPPED",
                JAVA8, JAVA7, JAVA6);
    }

    private static void runTomcatWrappedJava7(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "tomcat.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=TOMCAT_JDBC_POOL_WRAPPED",
                JAVA8, JAVA7);
    }

    private static void runTomcatWrappedJava8(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "tomcat.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=TOMCAT_JDBC_POOL_WRAPPED",
                JAVA8);
    }

    private static void runGlassfishWrappedJava7(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "glassfish.jdbc.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=GLASSFISH_JDBC_POOL_WRAPPED",
                JAVA8, JAVA7);
    }

    private static void runGlassfishWrappedJava8(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "glassfish.jdbc.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=GLASSFISH_JDBC_POOL_WRAPPED",
                JAVA8);
    }

    private static void runHikariCp1xWrapped(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.artifactId", "HikariCP");
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=HIKARI_CP_WRAPPED", JAVA8,
                JAVA7, JAVA6);
    }

    private static void runHikariCpJava6Wrapped(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.artifactId", "HikariCP-java6");
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=HIKARI_CP_WRAPPED", JAVA8,
                JAVA7, JAVA6);
    }

    private static void runHikariCpJava6WrappedButNotJava6(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.artifactId", "HikariCP-java6");
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=HIKARI_CP_WRAPPED", JAVA8,
                JAVA7);
    }

    private static void runHikariCpJava7Wrapped(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.artifactId", "HikariCP-java7");
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=HIKARI_CP_WRAPPED", JAVA8,
                JAVA7);
    }

    private static void runHikariCpJava8Wrapped(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.artifactId", "HikariCP");
        Util.updateLibVersion(MODULE_PATH, "hikari.cp.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=HIKARI_CP_WRAPPED", JAVA8);
    }

    private static void runBitronix2xWrapped(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "bitronix.cp.groupId", "org.codehaus.btm");
        Util.updateLibVersion(MODULE_PATH, "bitronix.cp.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=BITRONIX_WRAPPED", JAVA8,
                JAVA7, JAVA6);
    }

    private static void runBitronix3xWrapped(String version) throws Exception {
        Util.updateLibVersion(MODULE_PATH, "bitronix.cp.groupId", "com.github.marcus-nl.btm");
        Util.updateLibVersion(MODULE_PATH, "bitronix.cp.version", version);
        Util.runTests(MODULE_PATH, "-Dglowroot.test.jdbcConnectionType=BITRONIX_WRAPPED", JAVA8);
    }
}
