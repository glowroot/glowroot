/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.live;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.live.ClasspathCache.UiAnalyzedMethod;
import org.glowroot.agent.weaving.AnalyzedWorld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClasspathCacheTest {

    private static ClasspathCache classpathCache;

    @BeforeClass
    public static void setUp() throws Exception {
        ClassLoader badUrlsClassLoader =
                new URLClassLoader(new URL[] {new URL("file://a/b c"), new URL("http://a/b/c")});
        AnalyzedWorld analyzedWorld = mock(AnalyzedWorld.class);
        when(analyzedWorld.getClassLoaders()).thenReturn(
                ImmutableList.of(badUrlsClassLoader, ClassLoader.getSystemClassLoader()));
        Instrumentation instrumentation = mock(Instrumentation.class);
        when(instrumentation.getAllLoadedClasses()).thenReturn(new Class[] {A.class});
        classpathCache = new ClasspathCache(analyzedWorld, instrumentation);
    }

    @Test
    public void shouldRead() {
        // when
        List<String> classNames = classpathCache.getMatchingClassNames("google.common.base.str", 5);
        // then
        assertThat(classNames).contains("com.google.common.base.Strings");
    }

    @Test
    public void shouldReadFullClass() {
        // when
        List<String> classNames = classpathCache.getMatchingClassNames("ImmutableMap", 5);
        // then
        assertThat(classNames).contains("com.google.common.collect.ImmutableMap");
    }

    @Test
    public void shouldReadFullFullClass() {
        // when
        List<String> classNames =
                classpathCache.getMatchingClassNames("com.google.common.collect.ImmutableMap", 5);
        // then
        assertThat(classNames).contains("com.google.common.collect.ImmutableMap");
    }

    @Test
    public void shouldReadFullInnerClass() {
        // when
        List<String> classNames = classpathCache.getMatchingClassNames("OnePlusArrayList", 5);
        // then
        assertThat(classNames).contains("com.google.common.collect.Lists$OnePlusArrayList");
    }

    @Test
    public void shouldReadFullFullInnerClass() {
        // when
        List<String> classNames = classpathCache
                .getMatchingClassNames("com.google.common.collect.Lists$OnePlusArrayList", 5);
        // then
        assertThat(classNames).contains("com.google.common.collect.Lists$OnePlusArrayList");
    }

    @Test
    public void shouldHitFullMatchLimit() {
        // when
        List<String> classNames = classpathCache.getMatchingClassNames("Builder", 5);
        // then
        assertThat(classNames).hasSize(5);
        for (String className : classNames) {
            if (!className.endsWith("$Builder") && !className.endsWith(".Builder")) {
                throw new AssertionError();
            }
        }
    }

    @Test
    public void shouldAnalyzedMethods() {
        List<UiAnalyzedMethod> methods = classpathCache.getAnalyzedMethods(A.class.getName());
        assertThat(methods).hasSize(1);
    }

    @Test
    public void shouldAnalyzedMethodsB() {
        List<UiAnalyzedMethod> methods = classpathCache.getAnalyzedMethods(B.class.getName());
        assertThat(methods).hasSize(1);
    }

    @SuppressWarnings("serial")
    private static class A extends ArrayList<String> {
        @Override
        public boolean add(String str) {
            return true;
        }
    }

    @SuppressWarnings("serial")
    private static class B extends ArrayList<String> {
        @Override
        public boolean add(String str) {
            return true;
        }
    }
}
