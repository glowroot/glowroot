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
import java.io.IOException;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

// DO NOT USE ANY GUAVA CLASSES HERE
// they trigger loading of jul
// (and thus org.glowroot.agent.jul.Logger and thus glowroot's shaded slf4j)
class GlowrootDir {

    // windows filename reserved characters
    // cannot use guava CharMatcher as that triggers loading of jul (org.glowroot.agent.jul.Logger)
    private static final String RESERVED_CHARACTERS = "<>:\"/\\|?*";

    private GlowrootDir() {}

    static File getGlowrootDir(@Nullable File glowrootJarFile) {
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

    static File getAgentDir(File directory) throws IOException {
        String agentId = System.getProperty("glowroot.agent.id");
        if (agentId == null || agentId.isEmpty()) {
            return directory;
        }
        // "agent-" prefix is needed to ensure uniqueness
        File agentDir = new File(directory, "agent-" + makeSafeDirName(agentId));
        agentDir.mkdir();
        if (!agentDir.isDirectory()) {
            throw new IOException(
                    "Could not create agent directory: " + agentDir.getAbsolutePath());
        }
        return agentDir;
    }

    static File getLogDir(File agentDir) throws IOException {
        String logDirPath = System.getProperty("glowroot.log.dir");
        if (logDirPath == null || logDirPath.isEmpty()) {
            return agentDir;
        }
        File logDir = new File(logDirPath);
        logDir.mkdirs();
        if (!logDir.isDirectory()) {
            throw new IOException("Could not create log directory: " + logDir.getAbsolutePath());
        }
        String agentId = System.getProperty("glowroot.agent.id");
        if (agentId == null || agentId.isEmpty()) {
            return logDir;
        }
        logDir = new File(logDir, makeSafeDirName(agentId));
        logDir.mkdir();
        if (!logDir.isDirectory()) {
            throw new IOException("Could not create log directory: " + logDir.getAbsolutePath());
        }
        return logDir;
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
}
