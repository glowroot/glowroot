/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.microbenchmarks.core;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.SingleShotTime)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class WeavingBenchmark {

    private List<String> classNames;

    @Setup
    public void setup() throws IOException {
        classNames = new ArrayList<String>();
        URL jarURL = WeavingBenchmark.class.getProtectionDomain().getCodeSource().getLocation();
        JarFile jarFile = new JarFile(jarURL.getPath());
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
}
