/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.test;

import io.informant.testkit.AppUnderTest;
import io.informant.testkit.InformantContainer;
import io.informant.weaving.ParsedTypeCache;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// this cannot be tested using IsolatedWeavingClassLoader
public class ParsedTypePlanBTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @Test
    public void shouldNotLogWarningInParsedTypeCachePlanB() throws Exception {
        if (!InformantContainer.isExternalJvm()) {
            // this test is only relevant under javaagent
            // (tests are run under javaagent during mvn integration-test but not during mvn test)
            // not using org.junit.Assume which reports the test as ignored, since ignored tests
            // seem like something that needs to be revisited and 'un-ignored'
            return;
        }
        // given
        // when
        container.executeAppUnderTest(ShouldNotLogWarningInParsedTypeCachePlanB.class);
        // then
        // container close will validate that there were no unexpected warnings or errors
    }

    @Test
    public void shouldLogWarningInParsedTypeCachePlanB() throws Exception {
        if (!InformantContainer.isExternalJvm()) {
            // this test is only relevant under javaagent
            // (tests are run under javaagent during mvn integration-test but not during mvn test)
            // not using org.junit.Assume which reports the test as ignored, since ignored tests
            // seem like something that needs to be revisited and 'un-ignored'
            return;
        }
        // given
        container.addExpectedLogMessage(ParsedTypeCache.class.getName(),
                "could not find resource '" + Y.class.getName().replace('.', '/') + ".class'");
        // when
        container.executeAppUnderTest(ShouldLogWarningInParsedTypeCachePlanB.class);
        // then

    }

    public static class ShouldNotLogWarningInParsedTypeCachePlanB implements AppUnderTest {
        public void executeApp() throws Exception {
            Class.forName(Z.class.getName(), true, new DelegatingClassLoader());
        }
    }

    public static class ShouldLogWarningInParsedTypeCachePlanB implements AppUnderTest {
        public void executeApp() throws Exception {
            Class.forName(Z.class.getName(), true, new DelegatingClassLoader2());
        }
    }

    public static class DelegatingClassLoader extends ClassLoader {
        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {

            if (name.equals(Z.class.getName())) {
                return load(name);
            } else {
                return DelegatingClassLoader.class.getClassLoader().loadClass(name);
            }
        }
        protected Class<?> load(String name) throws ClassFormatError {
            byte[] bytes;
            try {
                bytes = Resources.toByteArray(Resources.getResource(name.replace('.', '/')
                        + ".class"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return super.defineClass(name, bytes, 0, bytes.length);
        }
        @Override
        public URL getResource(String name) {
            // don't load .class files as resources
            return null;
        }
        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            // don't load .class files as resources
            return null;
        }
    }

    public static class DelegatingClassLoader2 extends DelegatingClassLoader {
        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {

            if (name.equals(Y.class.getName()) || name.equals(Z.class.getName())) {
                return load(name);
            } else {
                return DelegatingClassLoader.class.getClassLoader().loadClass(name);
            }
        }
    }

    public static class Y {}

    public static class Z extends Y {}
}
