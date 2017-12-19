/*
 * Copyright 2011-2017 the original author or authors.
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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.common.util.OnlyUsedByTests;

// DO NOT USE ANY GUAVA CLASSES HERE because they trigger loading of jul
// (and thus org.glowroot.agent.jul.Logger and thus glowroot's shaded slf4j)
class Directories {

    // windows filename reserved characters
    // cannot use guava CharMatcher as that triggers loading of jul (org.glowroot.agent.jul.Logger)
    private static final String RESERVED_CHARACTERS = "<>:\"/\\|?*";

    private final File glowrootDir;
    private final @Nullable File pluginsDir;
    private final File confDir;
    private final @Nullable File sharedConfDir;
    private final File logDir;
    private final File tmpDir;
    private final @Nullable File glowrootJarFile;

    // these are needed for getDataDir()
    private final Properties props;
    private final @Nullable String agentId;
    private final LazyDefaultBaseDir lazyDefaultBaseDir;

    Directories(@Nullable File glowrootJarFile) throws IOException {
        glowrootDir = getGlowrootDir(glowrootJarFile);

        // check for glowroot.properties file in glowrootDir
        File propFile = new File(glowrootDir, "glowroot.properties");
        props = new Properties();
        if (propFile.exists()) {
            InputStream in = new FileInputStream(propFile);
            try {
                props.load(in);
            } finally {
                in.close();
            }
        }

        File pluginsDir = new File(glowrootDir, "plugins");
        this.pluginsDir = pluginsDir.exists() ? pluginsDir : null;

        // only look at system property, b/c only need agent-specific sub directories if there are
        // multiple agent.id values for a given installation
        agentId = System.getProperty("glowroot.agent.id");

        lazyDefaultBaseDir = new LazyDefaultBaseDir(glowrootDir, agentId);

        File confDir = getAgentDir("conf", props, agentId);
        if (confDir == null) {
            confDir = lazyDefaultBaseDir.get();
        }
        File logDir = getAgentDir("log", props, agentId);
        if (logDir == null) {
            logDir = lazyDefaultBaseDir.getSubDir("logs");
        }
        File tmpDir = getAgentDir("tmp", props, agentId);
        if (tmpDir == null) {
            tmpDir = lazyDefaultBaseDir.getSubDir("tmp");
        }

        this.confDir = confDir;
        this.logDir = logDir;
        this.tmpDir = tmpDir;
        this.glowrootJarFile = glowrootJarFile;

        sharedConfDir = confDir.equals(glowrootDir) ? null : glowrootDir;
    }

    @OnlyUsedByTests
    Directories(File testDir, @SuppressWarnings("unused") boolean dummy) throws IOException {
        glowrootDir = testDir;
        props = new Properties();
        agentId = null;
        lazyDefaultBaseDir = new LazyDefaultBaseDir(testDir, null);
        pluginsDir = null;
        confDir = testDir;
        sharedConfDir = null;
        logDir = testDir;
        tmpDir = lazyDefaultBaseDir.getSubDir("tmp");
        glowrootJarFile = null;
    }

    File getGlowrootDir() {
        return glowrootDir;
    }

    @Nullable
    File getPluginsDir() {
        return pluginsDir;
    }

    @Nullable
    File getSharedConfDir() {
        return sharedConfDir;
    }

    File getConfDir() {
        return confDir;
    }

    File getLogDir() {
        return logDir;
    }

    File getTmpDir() {
        return tmpDir;
    }

    @Nullable
    File getGlowrootJarFile() {
        return glowrootJarFile;
    }

    // only used by embedded agent
    File getDataDir() throws IOException {
        File dataDir = getAgentDir("data", props, agentId);
        if (dataDir == null) {
            return lazyDefaultBaseDir.getSubDir("data");
        } else {
            return dataDir;
        }
    }

    private static File getGlowrootDir(@Nullable File glowrootJarFile) {
        String testDirPath = System.getProperty("glowroot.test.dir");
        if (testDirPath != null && !testDirPath.isEmpty()) {
            return new File(testDirPath);
        }
        if (glowrootJarFile == null) {
            throw new IllegalStateException("Property glowroot.test.dir is required when running"
                    + " tests with no glowroot jar file");
        }
        File glowrootDir = glowrootJarFile.getParentFile();
        if (glowrootDir == null) {
            // the file does not name a parent, so it must be current dir
            return new File(".");
        }
        return glowrootDir;
    }

    private static @Nullable File getAgentDir(String shortName, Properties props,
            @Nullable String agentId) throws IOException {
        String dirPath = System.getProperty("glowroot." + shortName + ".dir");
        if (dirPath == null || dirPath.isEmpty()) {
            dirPath = props.getProperty(shortName + ".dir");
            if (dirPath == null || dirPath.isEmpty()) {
                return null;
            }
        }
        File dir = new File(dirPath);
        dir.mkdirs();
        if (!dir.isDirectory()) {
            throw new IOException("Could not create directory: " + dir.getAbsolutePath());
        }
        if (agentId == null || agentId.isEmpty()) {
            return dir;
        }
        File subDir = new File(dir, makeSafeDirName(agentId));
        subDir.mkdir();
        if (!subDir.isDirectory()) {
            throw new IOException("Could not create directory: " + subDir.getAbsolutePath());
        }
        return subDir;
    }

    @VisibleForTesting
    static String makeSafeDirName(String name) {
        StringBuilder safeName = new StringBuilder(name.length());
        int numTrailingDots = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (RESERVED_CHARACTERS.indexOf(c) == -1) {
                safeName.append(c);
            }
            if (c == '.') {
                numTrailingDots++;
            } else {
                numTrailingDots = 0;
            }
        }
        // windows directories ending with dot have issues
        if (numTrailingDots > 0) {
            safeName.setLength(safeName.length() - numTrailingDots);
        }
        return safeName.toString();
    }

    private static class LazyDefaultBaseDir {

        private final File glowrootDir;
        private final @Nullable String agentId;

        private @MonotonicNonNull File baseDir;

        private LazyDefaultBaseDir(File glowrootDir, @Nullable String agentId) {
            this.glowrootDir = glowrootDir;
            this.agentId = agentId;
        }

        private File get() throws IOException {
            if (baseDir == null) {
                baseDir = getBaseDir(glowrootDir, agentId);
            }
            return baseDir;
        }

        private File getSubDir(String name) throws IOException {
            File subDir = new File(get(), name);
            subDir.mkdir();
            if (!subDir.isDirectory()) {
                throw new IOException("Could not create directory: " + subDir.getAbsolutePath());
            }
            return subDir;
        }

        private static File getBaseDir(File glowrootDir, @Nullable String agentId)
                throws IOException {
            if (agentId == null || agentId.isEmpty()) {
                return glowrootDir;
            }
            // "agent-" prefix is needed to ensure uniqueness
            // (and to be visibly different from tmp and plugins directories)
            File baseDir = new File(glowrootDir, "agent-" + makeSafeDirName(agentId));
            baseDir.mkdir();
            if (!baseDir.isDirectory()) {
                throw new IOException("Could not create directory: " + baseDir.getAbsolutePath());
            }
            return baseDir;
        }
    }
}
