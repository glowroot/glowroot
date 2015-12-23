/*
 * Copyright 2014-2015 the original author or authors.
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

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import com.google.common.base.StandardSystemProperty;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;

// see https://github.com/netty/netty/issues/3233
// and https://bugs.openjdk.java.net/browse/JDK-8041920
public class MethodHandleRelatedCrashIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        assumeJdk8();
        container = Containers.createJavaagent();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        // need null check in case assumption is false in setUp()
        if (container != null) {
            container.close();
        }
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldNotCrashJvm() throws Exception {
        container.executeNoExpectedTrace(shouldNotCrashJvm.class);
    }

    public static class shouldNotCrashJvm implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("nashorn");
            try {
                for (int i = 0; i < 1000; i++) {
                    String js = "var map = Array.prototype.map;"
                            + "var names = ['john', 'jerry', 'bob'];"
                            + "var a = map.call(names, function(name) { return name.length() })";
                    engine.eval(js);
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }
        }
    }

    private static void assumeJdk8() {
        String javaVersion = StandardSystemProperty.JAVA_VERSION.value();
        Assume.assumeFalse(javaVersion.startsWith("1.6") || javaVersion.startsWith("1.7"));
    }
}
