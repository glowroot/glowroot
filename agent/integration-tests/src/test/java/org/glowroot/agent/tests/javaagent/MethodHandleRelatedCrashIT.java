/*
 * Copyright 2014-2018 the original author or authors.
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
package org.glowroot.agent.tests.javaagent;

import java.io.File;
import java.net.ServerSocket;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import org.junit.jupiter.api.*;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;

import static com.google.common.base.Charsets.UTF_8;

// see https://github.com/netty/netty/issues/3233
// and https://bugs.openjdk.java.net/browse/JDK-8041920
//
// this is to test NettyWorkaround.java, which is needed at least on Windows and Java 1.8.0_25
// (though not needed any more in Java 1.8.0_91)
public class MethodHandleRelatedCrashIT {

    private static File testDir;
    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        // javascript engine was removed in Java 15, but this test is only important for Java 8 anyways
        assumeJava8();
        // need to run with embedded=true so it starts up the Netty UI
        testDir = Files.createTempDir();
        File adminFile = new File(testDir, "admin.json");
        Files.write("{\"web\":{\"port\":" + getAvailablePort() + "}}", adminFile, UTF_8);
        container = new JavaagentContainer(testDir, true, ImmutableList.<String>of());
    }

    @AfterAll
    public static void tearDown() throws Exception {
        // need null check in case assumption is false in setUp()
        if (container != null) {
            container.close();
        }
        if (testDir != null) {
            TempDirs.deleteRecursively(testDir);
        }
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldNotCrashJvm() throws Exception {
        container.executeNoExpectedTrace(ShouldNotCrashJvm.class);
    }

    private static void assumeJava8() {
        Assumptions.assumeTrue(StandardSystemProperty.JAVA_VERSION.value().startsWith("1.8"));
    }

    private static int getAvailablePort() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }

    public static class ShouldNotCrashJvm implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByMimeType("application/javascript");
            try {
                for (int i = 0; i < 1000; i++) {
                    String js = "var map = Array.prototype.map;"
                            + "var names = ['john', 'jerry', 'bob'];"
                            + "var a = map.call(names, function(name) { return name.length })";
                    engine.eval(js);
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }
        }
    }
}
