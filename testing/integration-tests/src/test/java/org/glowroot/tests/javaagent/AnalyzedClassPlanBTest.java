/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.tests.javaagent;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.common.ClassNames;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.config.CapturePoint;
import org.glowroot.container.config.CapturePoint.CaptureKind;
import org.glowroot.weaving.AnalyzedWorld;

public class AnalyzedClassPlanBTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedJavaagentContainer();
        addCapturePoint();
        container.getConfigService().reweavePointcuts();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        // afterEachTest() will remove the pointcut configs, but still need to reweave here
        // in order to get back to square one
        container.getConfigService().reweavePointcuts();
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldNotLogWarningInAnalyzedWorldPlanB() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldNotLogWarningInAnalyzedWorldPlanB.class);
        // then
        // container close will validate that there were no unexpected warnings or errors
    }

    @Test
    public void shouldLogWarningInAnalyzedWorldPlanB() throws Exception {
        // given
        container.addExpectedLogMessage(AnalyzedWorld.class.getName(),
                Y.class.getName() + " was not woven with requested advice");
        // when
        container.executeAppUnderTest(ShouldLogWarningInAnalyzedWorldPlanB.class);
        // then
    }

    private static void addCapturePoint() throws Exception {
        CapturePoint config = new CapturePoint();
        config.setClassName("org.glowroot.tests.javaagent.AnalyzedClassPlanBTest$Y");
        config.setMethodName("y");
        config.setMethodParameterTypes(ImmutableList.<String>of());
        config.setMethodReturnType("");
        config.setCaptureKind(CaptureKind.METRIC);
        config.setMetricName("y");
        container.getConfigService().addCapturePoint(config);
    }

    public static class ShouldNotLogWarningInAnalyzedWorldPlanB implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            Class.forName(Z.class.getName(), true, new DelegatingClassLoader());
        }
    }

    public static class ShouldLogWarningInAnalyzedWorldPlanB implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            Class.forName(Z.class.getName(), true, new DelegatingClassLoader2());
        }
    }

    public static class DelegatingClassLoader extends ClassLoader {
        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {

            if (name.equals(Z.class.getName())) {
                try {
                    return load(name);
                } catch (IOException e) {
                    throw new ClassNotFoundException("Error loading class", e);
                }
            } else {
                return Class.forName(name, resolve, DelegatingClassLoader.class.getClassLoader());
            }
        }
        protected Class<?> load(String name) throws IOException {
            byte[] bytes = Resources.toByteArray(Resources.getResource(
                    ClassNames.toInternalName(name) + ".class"));
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
                try {
                    return load(name);
                } catch (IOException e) {
                    throw new ClassNotFoundException("Error loading class", e);
                }
            } else {
                return Class.forName(name, resolve, DelegatingClassLoader.class.getClassLoader());
            }
        }
    }

    public static class Y {
        public void y() {}
    }

    public static class Z extends Y {}
}
