/*
 * Copyright 2011-2019 the original author or authors.
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

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common.util.OnlyUsedByTests;

// LIMIT DEPENDENCY USAGE IN THIS CLASS SO IT DOESN'T TRIGGER ANY CLASS LOADING ON ITS OWN
public class Directories {

    // windows filename reserved characters
    // cannot use guava CharMatcher as that triggers loading of jul (org.glowroot.agent.jul.Logger)
    private static final String RESERVED_CHARACTERS = "<>:\"/\\|?*";

    private final @Nullable File glowrootJarFile;
    private final File glowrootDir;
    private final @Nullable File pluginsDir;
    private final List<File> confDirs;

    private final @Nullable Closeable agentDirLockCloseable;

    private final File logDir;
    private final File tmpDir;

    // these are needed for getDataDir()
    private final Properties rootProperties;
    private final boolean multiDir;
    private final @Nullable String agentId;
    private final Helper helper;

    public Directories(@Nullable File glowrootJarFile) throws Exception {
        this.glowrootJarFile = glowrootJarFile;
        glowrootDir = getGlowrootDir(glowrootJarFile);

        File pluginsDir = new File(glowrootDir, "plugins");
        this.pluginsDir = pluginsDir.exists() ? pluginsDir : null;

        confDirs = new ArrayList<File>();
        confDirs.add(glowrootDir);
        rootProperties = new Properties();
        File propFile = new File(glowrootDir, "glowroot.properties");
        if (propFile.exists()) {
            loadInto(propFile, rootProperties);
        }

        // explicit directories must be configured in the glowroot dir's glowroot.properties or via
        // system properties
        File explicitConfDir = getExplicitDir("conf", rootProperties);
        File explicitLogDir = getExplicitDir("log", rootProperties);
        File explicitTmpDir = getExplicitDir("tmp", rootProperties);

        if (explicitConfDir != null) {
            confDirs.add(explicitConfDir);
        }

        // multi.dir/glowroot.multi.dir must be configured in the glowroot dir's glowroot.properties
        // or via system properties
        multiDir = Boolean.getBoolean("glowroot.multi.dir")
                || Boolean.parseBoolean(rootProperties.getProperty("multi.dir"));

        String agentIdTemplate = getProperty("agent.id", rootProperties);

        String agentIdBeforeNumber = null;
        Integer agentNumber = null;
        Helper helper = null;
        File tmpDir = null;
        Closeable agentDirLockCloseable = null;
        if (agentIdTemplate != null) {
            Pattern pattern = Pattern.compile("\\{\\{(\\d+)\\.\\.(\\d+)}}$");
            Matcher matcher = pattern.matcher(agentIdTemplate);
            if (matcher.find()) {
                int from = Integer.parseInt(NotGuava.checkNotNull(matcher.group(1)));
                int to = Integer.parseInt(NotGuava.checkNotNull(matcher.group(2)));
                agentIdBeforeNumber = agentIdTemplate.substring(0, matcher.start());
                for (agentNumber = from; agentNumber <= to; agentNumber++) {
                    helper = new Helper(glowrootDir, multiDir, agentIdBeforeNumber,
                            agentNumber);
                    tmpDir = NotGuava.mkdirs(helper.getDir(explicitTmpDir, "tmp"));
                    agentDirLockCloseable = AgentDirsLocking.tryLockAgentDirs(tmpDir, false);
                    if (agentDirLockCloseable != null) {
                        break;
                    }
                }
            }
        }
        if (agentNumber == null) {
            agentId = agentIdTemplate;
            agentIdBeforeNumber = agentIdTemplate;
            helper = new Helper(glowrootDir, multiDir, agentId, null);
            tmpDir = NotGuava.mkdirs(helper.getDir(explicitTmpDir, "tmp"));
            agentDirLockCloseable = AgentDirsLocking.tryLockAgentDirs(tmpDir, true);
        } else {
            agentId = agentIdBeforeNumber + agentNumber;
            NotGuava.checkNotNull(helper);
            NotGuava.checkNotNull(tmpDir);
        }

        if (multiDir && agentId != null || agentNumber != null) {
            File confDir;
            if (explicitConfDir == null) {
                confDir = helper.getDefaultBaseDir();
            } else {
                // checkNotNull is safe because agentNumber != null ==> agentId != null
                confDir = NotGuava
                        .mkdirs(safelyNamedDir(explicitConfDir, NotGuava.checkNotNull(agentId)));
            }
            if (multiDir && agentNumber != null) {
                confDirs.add(NotGuava.checkNotNull(confDir.getParentFile()));
            }
            confDirs.add(confDir);
        }

        this.helper = helper;
        this.tmpDir = tmpDir;
        this.agentDirLockCloseable = agentDirLockCloseable;

        logDir = NotGuava.mkdirs(helper.getDir(explicitLogDir, "logs"));

        // most specific first, e.g. searching for config-default.json
        Collections.reverse(confDirs);
    }

    @OnlyUsedByTests
    Directories(File testDir, @SuppressWarnings("unused") boolean dummy) throws Exception {
        glowrootJarFile = null;
        glowrootDir = testDir;
        pluginsDir = null;
        confDirs = Arrays.asList(testDir);
        rootProperties = new Properties();
        multiDir = false;
        agentId = null;
        helper = new Helper(testDir, false, null, null);
        logDir = testDir;
        tmpDir = NotGuava.mkdirs(helper.getDir(null, "tmp"));
        agentDirLockCloseable = AgentDirsLocking.tryLockAgentDirs(tmpDir, true);
    }

    boolean logStartupErrorMultiDirWithMissingAgentId() {
        return multiDir && agentId == null;
    }

    public @Nullable File getGlowrootJarFile() {
        return glowrootJarFile;
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

    public File getTmpDir() {
        return tmpDir;
    }

    public @Nullable Closeable getAgentDirLockCloseable() {
        return agentDirLockCloseable;
    }

    @Nullable
    File getEmbeddedCollectorJarFile() {
        return getEmbeddedCollectorJarFile(glowrootJarFile);
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

    @Nullable
    File getLoggingLogstashJarFile() {
        if (glowrootJarFile == null) {
            return null;
        }
        File libDir = new File(glowrootJarFile.getParentFile(), "lib");
        if (!libDir.exists() || !libDir.isDirectory()) {
            return null;
        }
        File jarFile = new File(libDir, "glowroot-logging-logstash.jar");
        return jarFile.exists() ? jarFile : null;
    }

    // only used by embedded agent
    public File getDataDir() throws IOException {
        File explicitDataDir = getExplicitDir("data", rootProperties);
        return NotGuava.mkdirs(helper.getDir(explicitDataDir, "data"));
    }

    // only used by offline viewer
    public boolean hasDataDir() throws IOException {
        File explicitDataDir = getExplicitDir("data", rootProperties);
        File dataDir = helper.getDir(explicitDataDir, "data");
        return dataDir.exists();
    }

    static @Nullable File getEmbeddedCollectorJarFile(@Nullable File glowrootJarFile) {
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

    private static @Nullable File getExplicitDir(String shortName, Properties rootProperties) {
        String explicitDirPath = getProperty(shortName + ".dir", rootProperties);
        if (explicitDirPath == null) {
            return null;
        } else {
            return new File(explicitDirPath);
        }
    }

    private static @Nullable String getProperty(String name, Properties rootProperties) {
        String value = normalizePropertyValue(System.getProperty("glowroot." + name));
        if (value != null) {
            return value;
        }
        value = normalizePropertyValue(rootProperties.getProperty(name));
        if (value != null) {
            return value;
        }
        return null;
    }

    private static @Nullable String normalizePropertyValue(@Nullable String propertyValue) {
        if (propertyValue == null) {
            return null;
        }
        String trimmed = propertyValue.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @VisibleForTesting
    private static File safelyNamedDir(File parentDir, String name) throws IOException {
        String safeName = makeSafeDirName(name, true);
        File dir = new File(parentDir, safeName);
        if (!dir.exists() && !safeName.equals(name)) {
            String oldFormatSafeName = makeSafeDirName(name, false);
            File oldFormatDir = new File(parentDir, oldFormatSafeName);
            if (oldFormatDir.exists()) {
                // upgrade from 0.10.12 to 0.11.0
                if (!oldFormatDir.renameTo(dir)) {
                    throw new IOException("Unable to rename directory '"
                            + oldFormatDir.getAbsolutePath() + "' to '" + dir.getAbsolutePath()
                            + "' as part of upgrade to 0.11.0 or later");
                }
            }
        }
        return dir;
    }

    @VisibleForTesting
    static String makeSafeDirName(String name, boolean newFormat) {
        StringBuilder safeName = new StringBuilder(name.length());
        int numTrailingDots = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (RESERVED_CHARACTERS.indexOf(c) == -1) {
                safeName.append(c);
            } else if (newFormat) {
                safeName.append('-');
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

    private static void loadInto(File propFile, Properties props) throws IOException {
        InputStream in = new FileInputStream(propFile);
        try {
            props.load(in);
        } finally {
            in.close();
        }
    }

    private static class Helper {

        private final File glowrootDir;
        private final boolean multiDir;
        private final @Nullable String agentIdBeforeNumber;
        private final @Nullable Integer agentNumber;

        private @MonotonicNonNull File defaultBaseDir;

        private Helper(File glowrootDir, boolean multiDir, @Nullable String agentIdBeforeNumber,
                @Nullable Integer agentNumber) {
            this.glowrootDir = glowrootDir;
            this.multiDir = multiDir;
            this.agentIdBeforeNumber = agentIdBeforeNumber;
            this.agentNumber = agentNumber;
        }

        private File getDir(@Nullable File explicitDir, String name) throws IOException {
            if (explicitDir == null) {
                return new File(getDefaultBaseDir(), name);
            } else if (multiDir && agentIdBeforeNumber != null) {
                File dir = safelyNamedDir(explicitDir, agentIdBeforeNumber);
                if (agentNumber == null) {
                    return dir;
                } else {
                    return new File(dir, Integer.toString(agentNumber));
                }
            } else {
                return explicitDir;
            }
        }

        private File getDefaultBaseDir() throws IOException {
            if (defaultBaseDir == null) {
                defaultBaseDir = createDefaultBaseDir();
            }
            return defaultBaseDir;
        }

        private File createDefaultBaseDir() throws IOException {
            if (multiDir && agentIdBeforeNumber != null) {
                File dir = getGlowrootSubDir(agentIdBeforeNumber);
                if (agentNumber == null) {
                    return dir;
                } else {
                    return NotGuava.mkdirs(new File(dir, Integer.toString(agentNumber)));
                }
            } else if (agentNumber == null) {
                return glowrootDir;
            } else {
                return NotGuava.mkdirs(getGlowrootSubDir(agentIdBeforeNumber + agentNumber));
            }
        }

        private File getGlowrootSubDir(String name) throws IOException {
            // "agent-" prefix is needed to ensure uniqueness
            // (and to be visibly different from tmp and plugins directories)
            return safelyNamedDir(glowrootDir, "agent-" + name);
        }
    }
}
