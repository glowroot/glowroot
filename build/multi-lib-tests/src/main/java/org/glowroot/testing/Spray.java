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

public class Spray {

    private static final String MODULE_PATH = "agent/plugins/spray-plugin";

    public static void main(String[] args) throws Exception {
        run("1.1.0", "", "2.1.4", "2.10.6", JAVA6, JAVA7, JAVA8);
        run("1.1.1", "", "2.1.4", "2.10.6", JAVA6, JAVA7, JAVA8);
        run("1.1.2", "", "2.1.4", "2.10.6", JAVA6, JAVA7, JAVA8);
        run("1.1.3", "", "2.1.4", "2.10.6", JAVA6, JAVA7, JAVA8);
        run("1.2.0", "", "2.2.4", "2.10.6", JAVA6, JAVA7, JAVA8);
        run("1.2.1", "", "2.2.4", "2.10.6", JAVA6, JAVA7, JAVA8);
        run("1.2.2", "", "2.2.4", "2.10.6", JAVA6, JAVA7, JAVA8);
        run("1.2.3", "", "2.2.4", "2.10.6", JAVA6, JAVA7, JAVA8);
        run("1.3.1", "_2.11", "2.4.6", "2.11.8", JAVA8);
        run("1.3.2", "_2.11", "2.4.6", "2.11.8", JAVA8);
        run("1.3.3", "_2.11", "2.4.6", "2.11.8", JAVA8);
        run("1.3.4", "_2.11", "2.4.6", "2.11.8", JAVA8);
    }

    private static void run(String sprayVersion, String sprayArtifactExt, String akkaVersion,
            String scalaVersion, JavaVersion... javaVersions) throws Exception {
        String scalaMajorVersion = scalaVersion.substring(0, scalaVersion.lastIndexOf("."));
        Util.updateLibVersion(MODULE_PATH, "spray.version", sprayVersion);
        Util.updateLibVersion(MODULE_PATH, "spray.artifact.ext", sprayArtifactExt);
        Util.updateLibVersion(MODULE_PATH, "akka.version", akkaVersion);
        Util.updateLibVersion(MODULE_PATH, "scala.major.version", scalaMajorVersion);
        Util.updateLibVersion(MODULE_PATH, "scala.version", scalaVersion);
        Util.runTests(MODULE_PATH, javaVersions);
    }
}
