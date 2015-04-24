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
package org.glowroot;

import java.io.File;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

class DataDir {

    private static final Logger logger = LoggerFactory.getLogger(DataDir.class);

    private DataDir() {}

    public static File getDataDir(Map<String, String> properties, @Nullable File glowrootJarFile) {
        String dataDirPath = properties.get("data.dir");
        if (glowrootJarFile == null) {
            // this is only for test support
            checkNotNull(dataDirPath, "Property data.dir is required when no glowroot jar file");
            return new File(dataDirPath);
        }
        // empty check to support parameterized script, e.g. -Dglowroot.data.dir=${somevar}
        if (Strings.isNullOrEmpty(dataDirPath)) {
            return getDefaultDataDir(glowrootJarFile);
        }
        File dataDir = new File(dataDirPath);
        if (!dataDir.isAbsolute()) {
            return getRelativeDataDir(dataDirPath, glowrootJarFile);
        }
        return getAbsoluteDataDir(dataDir);
    }

    private static File getDefaultDataDir(File glowrootJarFile) {
        File glowrootDir = glowrootJarFile.getParentFile();
        if (glowrootDir == null) {
            // the file does not name a parent, so it must be current dir
            return new File(".");
        }
        return glowrootDir;
    }

    // resolve path relative to glowroot dir instead of process current dir if possible
    private static File getRelativeDataDir(String dataDirPath, File glowrootJarFile) {
        File dataDir = new File(glowrootJarFile.getParentFile(), dataDirPath);
        return getAbsoluteDataDir(dataDir);
    }

    private static File getAbsoluteDataDir(File dataDir) {
        dataDir.mkdirs();
        if (!dataDir.isDirectory()) {
            File processCurrDir = new File(".");
            logger.warn("error creating data directory: {} (using directory {} instead)",
                    dataDir.getAbsolutePath(), processCurrDir.getAbsolutePath());
            return processCurrDir;
        }
        return dataDir;
    }
}
