/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container;

import java.io.File;

import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import org.checkerframework.checker.nullness.qual.Nullable;

public class ClassPath {

    private ClassPath() {}

    @Nullable
    public static File getGlowrootCoreJarFile() {
        return getJarFile("glowroot-core-[0-9.]+(-SNAPSHOT)?.jar");
    }

    @Nullable
    private static File getJarFile(String pattern) {
        String classpath = StandardSystemProperty.JAVA_CLASS_PATH.value();
        if (classpath == null) {
            return null;
        }
        for (String path : Splitter.on(File.pathSeparator).split(classpath)) {
            File file = new File(path);
            if (file.getName().matches(pattern)) {
                return file;
            }
        }
        return null;
    }
}
