/*
 * Copyright 2011-2015 the original author or authors.
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

import com.google.common.base.StandardSystemProperty;
import org.fest.reflect.core.Reflection;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;

// from http://docs.oracle.com/javase/7/docs/api/java/lang/invoke/MethodHandle.html:
//
// "Implementations may (or may not) create internal subclasses of MethodHandle"
//
// when these internal subclasses of MethodHandle are created, they are passed to
// ClassFileTransformer.transform() with null class name
//
// this test checks that WeavingClassFileTransformer.transform() doesn't mind being passed null
// class names
public class MethodHandleWeavingIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        assumeJdk7();
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
    public void shouldReadTraces() throws Exception {
        // given
        // when
        container.executeNoExpectedTrace(ShouldDefineAnonymousClass.class);
        // then
    }

    public static class ShouldDefineAnonymousClass implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            Class<?> methodHandlesClass = Class.forName("java.lang.invoke.MethodHandles");

            Object lookup = Reflection.staticMethod("lookup")
                    .in(methodHandlesClass)
                    .invoke();

            Class<?> methodTypeClass = Class.forName("java.lang.invoke.MethodType");

            Object methodType = Reflection.staticMethod("methodType")
                    .withParameterTypes(Class.class)
                    .in(methodTypeClass)
                    .invoke(String.class);

            Reflection.method("findVirtual")
                    .withParameterTypes(Class.class, String.class, methodTypeClass)
                    .in(lookup)
                    .invoke(Object.class, "toString", methodType);
        }
    }

    private static void assumeJdk7() {
        Assume.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("1.6"));
    }
}
