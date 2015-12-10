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
package org.glowroot.agent;

import java.io.File;

import javax.annotation.Nullable;

import com.google.common.base.Strings;

import static com.google.common.base.Preconditions.checkNotNull;

class BaseDir {

    private BaseDir() {}

    public static File getBaseDir(@Nullable String baseDirPath, @Nullable File glowrootJarFile) {
        if (glowrootJarFile == null) {
            // this is only for test support
            checkNotNull(baseDirPath, "Property base.dir is required when no glowroot jar file");
            return new File(baseDirPath);
        }
        // empty check to support parameterized script, e.g. -Dglowroot.base.dir=${somevar}
        if (Strings.isNullOrEmpty(baseDirPath)) {
            return getDefaultBaseDir(glowrootJarFile);
        }
        File baseDir = new File(baseDirPath);
        if (!baseDir.isAbsolute()) {
            return getRelativeBaseDir(baseDirPath, glowrootJarFile);
        }
        return getAbsoluteBaseDir(baseDir);
    }

    private static File getDefaultBaseDir(File glowrootJarFile) {
        File glowbaseDir = glowrootJarFile.getParentFile();
        if (glowbaseDir == null) {
            // the file does not name a parent, so it must be current dir
            return new File(".");
        }
        return glowbaseDir;
    }

    // resolve path relative to glowroot dir instead of process current dir if possible
    private static File getRelativeBaseDir(String baseDirPath, File glowrootJarFile) {
        File baseDir = new File(glowrootJarFile.getParentFile(), baseDirPath);
        return getAbsoluteBaseDir(baseDir);
    }

    private static File getAbsoluteBaseDir(File baseDir) {
        baseDir.mkdirs();
        if (!baseDir.isDirectory()) {
            File processCurrDir = new File(".");
            // not using logger since the base dir is needed to set up the logger
            return processCurrDir;
        }
        return baseDir;
    }
}
