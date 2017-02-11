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

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;

class GlowrootDir {

    // windows filename reserved characters
    private static final CharMatcher RESERVED_CHARACTER_MATCHER = CharMatcher.anyOf("<>:\"/\\|?*");
    private static final CharMatcher DOT_MATCHER = CharMatcher.anyOf(".");

    private GlowrootDir() {}

    public static File getGlowrootDir(@Nullable File glowrootJarFile) {
        String testDirPath = System.getProperty("glowroot.test.dir");
        if (!Strings.isNullOrEmpty(testDirPath)) {
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

    public static File getAgentDir(File glowrootDir) throws IOException {
        String agentId = System.getProperty("glowroot.agent.id");
        if (Strings.isNullOrEmpty(agentId)) {
            return glowrootDir;
        }
        File agentDir = new File(glowrootDir, "agent-" + makeSafeDirName(agentId));
        agentDir.mkdir();
        if (!agentDir.isDirectory()) {
            throw new IOException(
                    "Could not create agent directory: " + agentDir.getAbsolutePath());
        }
        return agentDir;
    }

    private static String makeSafeDirName(String name) {
        // windows directories ending with dot have issues
        return DOT_MATCHER.trimTrailingFrom(RESERVED_CHARACTER_MATCHER.removeFrom(name));
    }
}
