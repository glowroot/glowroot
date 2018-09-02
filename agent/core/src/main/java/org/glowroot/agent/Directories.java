/*
 * Copyright 2011-2018 the original author or authors.
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common.util.OnlyUsedByTests;

// LIMIT DEPENDENCY USAGE IN THIS CLASS SO IT DOESN'T TRIGGER ANY CLASS LOADING ON ITS OWN
public class Directories {

    // windows filename reserved characters
    // cannot use guava CharMatcher as that triggers loading of jul (org.glowroot.agent.jul.Logger)
    private static final String RESERVED_CHARACTERS = "<>:\"/\\|?*";

    private final File glowrootDir;
    private final @Nullable File pluginsDir;
    private final List<File> confDirs;
    private final File logDir;
    private final File tmpDir;
    private final @Nullable File glowrootJarFile;

    // these are needed for getDataDir()
    private final Properties props;
    private final @Nullable String agentId;
    private final LazyDefaultBaseDir lazyDefaultBaseDir;

    public Directories(@Nullable File glowrootJarFile) throws IOException {
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

        List<File> confDirs = getConfDirs(props, agentId, glowrootDir);
        if (confDirs.isEmpty()) {
            confDirs = lazyDefaultBaseDir.getConfDirs();
        }
        File logDir = getAgentExplicitDir("log", props, agentId);
        if (logDir == null) {
            logDir = lazyDefaultBaseDir.getDir("logs");
        }
        File tmpDir = getAgentExplicitDir("tmp", props, agentId);
        if (tmpDir == null) {
            tmpDir = lazyDefaultBaseDir.getDir("tmp");
        }

        this.confDirs = confDirs;
        this.logDir = logDir;
        this.tmpDir = tmpDir;
        this.glowrootJarFile = glowrootJarFile;
    }

    @OnlyUsedByTests
    Directories(File testDir, @SuppressWarnings("unused") boolean dummy) throws IOException {
        glowrootDir = testDir;
        props = new Properties();
        agentId = null;
        lazyDefaultBaseDir = new LazyDefaultBaseDir(testDir, null);
        pluginsDir = null;
        confDirs = Arrays.asList(testDir);
        logDir = testDir;
        tmpDir = lazyDefaultBaseDir.getDir("tmp");
        mkdirs(tmpDir);
        glowrootJarFile = null;
    }

    File getGlowrootDir() {
        return glowrootDir;
    }

    @Nullable
    File getPluginsDir() {
        return pluginsDir;
    }

    public List<File> getConfDirs() {
        return confDirs;
    }

    public File getConfDir() {
        return confDirs.get(0);
    }

    public File getLogDir() {
        return logDir;
    }

    File getTmpDir() {
        return tmpDir;
    }

    @Nullable
    File getGlowrootJarFile() {
        return glowrootJarFile;
    }

    @Nullable
    File getEmbeddedCollectorJarFile() {
        if (glowrootJarFile == null) {
            return null;
        }
        File libDir = new File(glowrootJarFile.getParentFile(), "lib");
        if (!libDir.exists() || !libDir.isDirectory()) {
            return null;
        }
        File jarFile = new File(libDir, "glowroot-embedded-collector.jar");
        return jarFile.exists() ? jarFile : null;
    }

    @Nullable
    File getCentralCollectorHttpsJarFile(String normalizedOsName) {
        if (glowrootJarFile == null) {
            return null;
        }
        File libDir = new File(glowrootJarFile.getParentFile(), "lib");
        if (!libDir.exists() || !libDir.isDirectory()) {
            return null;
        }
        File jarFile =
                new File(libDir, "glowroot-central-collector-https-" + normalizedOsName + ".jar");
        return jarFile.exists() ? jarFile : null;
    }

    // only used by embedded agent
    public File getDataDir() throws IOException {
        File dataDir = getAgentExplicitDir("data", props, agentId);
        if (dataDir == null) {
            dataDir = lazyDefaultBaseDir.getDir("data");
        }
        mkdirs(dataDir);
        return dataDir;
    }

    // only used by offline viewer
    public boolean hasDataDir() throws IOException {
        File dataDir = getAgentExplicitDir("data", props, agentId);
        if (dataDir == null) {
            dataDir = lazyDefaultBaseDir.getDir("data");
        }
        return dataDir.exists();
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

    private static List<File> getConfDirs(Properties props, @Nullable String agentId,
            File glowrootDir) throws IOException {
        File explicitDir = getExplicitDir("conf", props);
        if (explicitDir == null) {
            return Collections.emptyList();
        }
        if (agentId == null || agentId.isEmpty()) {
            return Arrays.asList(mkdirs(explicitDir), glowrootDir);
        } else {
            File subDir = new File(explicitDir, makeSafeDirName(agentId));
            return Arrays.asList(mkdirs(subDir), explicitDir, glowrootDir);
        }
    }

    private static @Nullable File getAgentExplicitDir(String shortName, Properties props,
            @Nullable String agentId) throws IOException {
        File explicitDir = getExplicitDir(shortName, props);
        if (explicitDir == null) {
            return null;
        }
        if (agentId == null || agentId.isEmpty()) {
            return mkdirs(explicitDir);
        }
        return mkdirs(new File(explicitDir, makeSafeDirName(agentId)));
    }

    private static @Nullable File getExplicitDir(String shortName, Properties props) {
        String dirPath = System.getProperty("glowroot." + shortName + ".dir");
        if (dirPath == null || dirPath.isEmpty()) {
            dirPath = props.getProperty(shortName + ".dir");
            if (dirPath == null || dirPath.isEmpty()) {
                return null;
            }
        }
        return new File(dirPath);
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

    private static File mkdirs(File dir) throws IOException {
        dir.mkdirs();
        if (!dir.isDirectory()) {
            throw new IOException("Could not create directory: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static class LazyDefaultBaseDir {

        private final File glowrootDir;
        private final @Nullable String agentId;

        private @MonotonicNonNull File baseDir;

        private LazyDefaultBaseDir(File glowrootDir, @Nullable String agentId) {
            this.glowrootDir = glowrootDir;
            this.agentId = agentId;
        }

        private File getDir(String name) throws IOException {
            return mkdirs(new File(getBaseDir(), name));
        }

        private List<File> getConfDirs() throws IOException {
            File baseDir = getBaseDir();
            if (agentId == null || agentId.isEmpty()) {
                return Arrays.asList(baseDir);
            } else {
                return Arrays.asList(baseDir, glowrootDir);
            }
        }

        private File getBaseDir() throws IOException {
            if (baseDir == null) {
                baseDir = getBaseDir(glowrootDir, agentId);
            }
            return baseDir;
        }

        private static File getBaseDir(File glowrootDir, @Nullable String agentId)
                throws IOException {
            if (agentId == null || agentId.isEmpty()) {
                return glowrootDir;
            }
            // "agent-" prefix is needed to ensure uniqueness
            // (and to be visibly different from tmp and plugins directories)
            return mkdirs(new File(glowrootDir, "agent-" + makeSafeDirName(agentId)));
        }
    }
}
