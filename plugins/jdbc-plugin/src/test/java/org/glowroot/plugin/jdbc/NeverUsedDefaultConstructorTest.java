/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.plugin.jdbc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.container.Container;

// currently Jacoco counts private default constructors on utility classes as uncovered lines of
// code when really they are there to enforce that the constructor can never be called
// see https://github.com/jacoco/jacoco/wiki/FilteringOptions
// #filters-for-code-where-test-execution-is-questionable-or-impossible-by-design
//
// also see copies of this in glowroot-core, servlet-plugin and logger-plugin
public class NeverUsedDefaultConstructorTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedLocalContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @Test
    public void testNeverUsedDefaultConstructors() throws Exception {
        ClassLoader loader = NeverUsedDefaultConstructorTest.class.getClassLoader();
        ImmutableSet<ClassInfo> classInfos = ClassPath.from(loader).getAllClasses();
        for (ClassInfo classInfo : classInfos) {
            if (!classInfo.getName().startsWith("org.glowroot.plugin.jdbc")) {
                continue;
            }
            Class<?> jdbcPluginClass = classInfo.load();
            try {
                testDefaultConstructorIfPointcutAdviceClass(jdbcPluginClass);
            } catch (Exception e) {
            }
            try {
                testPrivateDefaultConstructorIfUtilityClass(jdbcPluginClass);
            } catch (Exception e) {
            }
        }
    }

    private void testPrivateDefaultConstructorIfUtilityClass(Class<?> clazz) throws Exception {
        Constructor<?> defaultConstructor = clazz.getDeclaredConstructor();
        if (!Modifier.isPrivate(defaultConstructor.getModifiers())) {
            return;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                return;
            }
        }
        defaultConstructor.setAccessible(true);
        defaultConstructor.newInstance();
    }

    private void testDefaultConstructorIfPointcutAdviceClass(Class<?> clazz) throws Exception {
        if (!clazz.isAnnotationPresent(Pointcut.class)) {
            return;
        }
        Constructor<?> defaultConstructor = clazz.getDeclaredConstructor();
        defaultConstructor.setAccessible(true);
        defaultConstructor.newInstance();
        // also test enclosing "aspect" default constructor
        clazz = clazz.getEnclosingClass();
        if (clazz == null) {
            return;
        }
        defaultConstructor = clazz.getDeclaredConstructor();
        defaultConstructor.setAccessible(true);
        defaultConstructor.newInstance();
    }
}
