/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.central;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common.util.PropertiesFiles;

class Directories {

    private final File confDir;
    private final File logDir;

    Directories(File centralDir) throws IOException {
        File propFile = new File(centralDir, "glowroot-central.properties");
        Properties props;
        if (propFile.exists()) {
            props = PropertiesFiles.load(propFile);
        } else {
            props = new Properties();
        }

        File confDir = getDir("conf", props);
        if (confDir == null) {
            confDir = mkdirs(centralDir);
        }
        File logDir = getDir("log", props);
        if (logDir == null) {
            logDir = mkdirs(new File(centralDir, "logs"));
        }

        this.confDir = confDir;
        this.logDir = logDir;
    }

    File getConfDir() {
        return confDir;
    }

    File getLogDir() {
        return logDir;
    }

    // similar method from agent org.glowroot.agent.Directories
    private static @Nullable File getDir(String shortName, Properties props)
            throws IOException {
        String dirPath = System.getProperty("glowroot." + shortName + ".dir");
        if (dirPath == null || dirPath.isEmpty()) {
            dirPath = props.getProperty(shortName + ".dir");
            if (dirPath == null || dirPath.isEmpty()) {
                return null;
            }
        }
        return mkdirs(new File(dirPath));
    }

    // same method from agent org.glowroot.agent.Directories
    private static File mkdirs(File dir) throws IOException {
        dir.mkdirs();
        if (!dir.isDirectory()) {
            throw new IOException("Could not create directory: " + dir.getAbsolutePath());
        }
        return dir;
    }
}
