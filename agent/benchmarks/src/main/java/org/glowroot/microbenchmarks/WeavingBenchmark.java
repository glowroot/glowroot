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
package org.glowroot.microbenchmarks;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.SingleShotTime)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class WeavingBenchmark {

    private Set<String> classNames;

    @Setup
    public void setup() throws IOException {
        classNames = new HashSet<String>();
        for (String path : getClassPath()) {
            if (!path.endsWith(".jar")) {
                continue;
            }
            JarFile jarFile = new JarFile(path);
            try {
                Enumeration<JarEntry> e = jarFile.entries();
                while (e.hasMoreElements()) {
                    JarEntry jarEntry = e.nextElement();
                    String name = jarEntry.getName();
                    if (name.startsWith("org/springframework/") && name.endsWith(".class")) {
                        name = name.replace('/', '.');
                        name = name.substring(0, name.length() - ".class".length());
                        classNames.add(name);
                    }
                }
            } finally {
                jarFile.close();
            }
        }
    }

    @TearDown
    public void tearDown() throws InterruptedException {
        Thread.sleep(100);
    }

    @Benchmark
    public void execute() throws ClassNotFoundException {
        for (String className : classNames) {
            try {
                Class.forName(className, false, WeavingBenchmark.class.getClassLoader());
            } catch (NoClassDefFoundError e) {
                // optional dependencies are not transitively included
            }
        }
    }

    private static List<String> getClassPath() {
        String classPath = StandardSystemProperty.JAVA_CLASS_PATH.value();
        return Splitter.on(StandardSystemProperty.PATH_SEPARATOR.value()).splitToList(classPath);
    }
}
