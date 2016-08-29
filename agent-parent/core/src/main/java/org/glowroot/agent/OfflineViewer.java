/*
 * Copyright 2012-2016 the original author or authors.
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
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.sql.SQLException;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import org.h2.tools.Recover;
import org.h2.tools.RunScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OfflineViewer {

    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private OfflineViewer() {}

    public static void main(String... args) throws Exception {
        CodeSource codeSource = OfflineViewer.class.getProtectionDomain().getCodeSource();
        File glowrootJarFile = getGlowrootJarFile(codeSource);
        if (args.length == 1 && args[0].equals("recover")) {
            recover(glowrootJarFile);
            return;
        }
        MainEntryPoint.runViewer(glowrootJarFile);
    }

    @VisibleForTesting
    static @Nullable File getGlowrootJarFile(@Nullable CodeSource codeSource)
            throws URISyntaxException {
        if (codeSource == null) {
            return null;
        }
        File codeSourceFile = new File(codeSource.getLocation().toURI());
        if (codeSourceFile.getName().endsWith(".jar")) {
            return codeSourceFile;
        }
        return null;
    }

    private static void recover(@Nullable File glowrootJarFile) throws SQLException {
        String baseDirPath = System.getProperty("glowroot.base.dir");
        File baseDir = BaseDir.getBaseDir(baseDirPath, glowrootJarFile);
        File dataDir = new File(baseDir, "data");
        File recoverFile = new File(dataDir, "data.h2.sql");
        if (recoverFile.exists()) {
            if (!recoverFile.delete()) {
                startupLogger.warn("recover failed: cannot delete existing data.h2.sql");
            }
        }
        Recover.main(new String[] {"-dir", dataDir.getPath(), "-db", "data"});
        File dbFile = new File(dataDir, "data.h2.db");
        File dbBakFile = new File(dataDir, "data.h2.db.bak");
        if (dbBakFile.exists()) {
            if (!dbBakFile.delete()) {
                startupLogger.warn("recover failed, cannot delete existing file: {}",
                        dbBakFile.getPath());
            }
        }
        if (!dbFile.renameTo(dbBakFile)) {
            startupLogger.warn("recover failed, cannot rename {} to {}", dbFile.getPath(),
                    dbBakFile.getPath());
            return;
        }
        RunScript.main(
                new String[] {"-url", "jdbc:h2:" + dataDir.getPath() + File.separator + "data",
                        "-script", recoverFile.getPath()});
        startupLogger.info("recover succeeded");

        // clean up
        if (!dbBakFile.delete()) {
            startupLogger.info("failed to clean-up, cannot delete file: {}", dbBakFile.getPath());
        }
        if (!recoverFile.delete()) {
            startupLogger.info("failed to clean-up, cannot delete file: {}", recoverFile.getPath());
        }
    }
}
