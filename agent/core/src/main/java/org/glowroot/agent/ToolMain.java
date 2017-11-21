/*
 * Copyright 2012-2017 the original author or authors.
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
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.security.CodeSource;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.h2.tools.Console;
import org.h2.tools.Recover;
import org.h2.tools.RunScript;
import org.h2.tools.Script;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ToolMain {

    // need to wait to init logger until
    private static volatile @MonotonicNonNull Logger startupLogger;

    private ToolMain() {}

    public static void main(String[] args) throws Exception {
        CodeSource codeSource = ToolMain.class.getProtectionDomain().getCodeSource();
        File glowrootJarFile = getGlowrootJarFile(codeSource);
        Directories directories = new Directories(glowrootJarFile);
        MainEntryPoint.initLogging(directories.getConfDir(), directories.getSharedConfDir(),
                directories.getLogDir());
        startupLogger = LoggerFactory.getLogger("org.glowroot");

        if (args.length == 0) {
            MainEntryPoint.runViewer(directories);
            return;
        }
        String command = args[0];
        if (command.equals("h2")) {
            String subcommand = args[1];
            if (subcommand.equals("console") && args.length == 2) {
                console(directories.getDataDir());
                return;
            } else if (subcommand.equals("recreate") && args.length == 2) {
                recreate(directories.getDataDir());
                return;
            } else if (subcommand.equals("recover") && args.length == 2) {
                recover(directories.getDataDir());
                return;
            }
        } else if (command.equals("mask-central-data") && args.length == 1) {
            // this is for monitoring glowroot central with glowroot agent, and then masking the
            // data captured from glowroot central so that it can be shared for debugging issues
            // within glowroot central
            maskCentralData(directories.getDataDir());
            return;
        }
        startupLogger.error("unexpected args, exiting");
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

    private static void console(File dataDir) throws Exception {
        Console.main("-url", "jdbc:h2:" + dataDir.getPath() + File.separator + "data", "-user",
                "sa");
    }

    @RequiresNonNull("startupLogger")
    private static void recreate(File dataDir) throws Exception {
        File backupFile = new File(dataDir, "backup.sql");
        if (backupFile.exists() && !backupFile.delete()) {
            startupLogger.warn("recreate failed: cannot delete existing backup.sql");
        }
        Script.main("-url", "jdbc:h2:" + dataDir.getPath() + File.separator + "data", "-user", "sa",
                "-script", backupFile.getPath());
        File dbFile = new File(dataDir, "data.h2.db");
        File dbBakFile = new File(dataDir, "data.h2.db.bak");
        if (dbBakFile.exists() && !dbBakFile.delete()) {
            startupLogger.warn("recreate failed, cannot delete existing file: {}",
                    dbBakFile.getPath());
        }
        if (!dbFile.renameTo(dbBakFile)) {
            startupLogger.warn("recreate failed, cannot rename {} to {}", dbFile.getPath(),
                    dbBakFile.getPath());
            return;
        }
        RunScript.main("-url", "jdbc:h2:" + dataDir.getPath() + File.separator + "data", "-script",
                backupFile.getPath());
        startupLogger.info("recreate succeeded");

        // clean up
        if (!dbBakFile.delete()) {
            startupLogger.info("failed to clean-up, cannot delete file: {}", dbBakFile.getPath());
        }
        if (!backupFile.delete()) {
            startupLogger.info("failed to clean-up, cannot delete file: {}", backupFile.getPath());
        }
    }

    @RequiresNonNull("startupLogger")
    private static void recover(File dataDir) throws Exception {
        File recoverFile = new File(dataDir, "data.h2.sql");
        if (recoverFile.exists() && !recoverFile.delete()) {
            startupLogger.warn("recover failed: cannot delete existing data.h2.sql");
        }
        Recover.main("-dir", dataDir.getPath(), "-db", "data");
        File dbFile = new File(dataDir, "data.h2.db");
        File dbBakFile = new File(dataDir, "data.h2.db.bak");
        if (dbBakFile.exists() && !dbBakFile.delete()) {
            startupLogger.warn("recover failed, cannot delete existing file: {}",
                    dbBakFile.getPath());
        }
        if (!dbFile.renameTo(dbBakFile)) {
            startupLogger.warn("recover failed, cannot rename {} to {}", dbFile.getPath(),
                    dbBakFile.getPath());
            return;
        }
        RunScript.main("-url", "jdbc:h2:" + dataDir.getPath() + File.separator + "data", "-script",
                recoverFile.getPath());
        startupLogger.info("recover succeeded");

        // clean up
        if (!dbBakFile.delete()) {
            startupLogger.info("failed to clean-up, cannot delete file: {}", dbBakFile.getPath());
        }
        if (!recoverFile.delete()) {
            startupLogger.info("failed to clean-up, cannot delete file: {}", recoverFile.getPath());
        }
    }

    @RequiresNonNull("startupLogger")
    private static void maskCentralData(File dataDir) throws Exception {
        File maskScriptFile = File.createTempFile("mask-central-data", ".sql");
        PrintWriter out = new PrintWriter(new FileWriter(maskScriptFile));
        try {
            // mask agent ids and agent rollup ids
            out.println("update trace set headline = left(headline, position(': ', headline) + 1)"
                    + " || " + applyHash("substr(headline, position(': ', headline) + 2)")
                    + " where transaction_type <> 'Web' and headline like '%: %';");
            // mask query strings
            out.println("update trace set headline = left(headline, position('?', headline))"
                    + " || " + applyHash("substr(headline, position('?', headline) + 1)")
                    + " where transaction_type = 'Web' and headline like '%?%';");
            // mask usernames
            out.println("update trace set user = " + applyHash("user")
                    + " where transaction_type = 'Web'" + " and user is not null;");
        } finally {
            out.close();
        }
        RunScript.main("-url", "jdbc:h2:" + dataDir.getPath() + File.separator + "data", "-user",
                "sa", "-script", maskScriptFile.getPath());
        if (!maskScriptFile.delete()) {
            startupLogger.info("failed to clean-up, cannot delete file: {}",
                    maskScriptFile.getPath());
        }
        // re-create data file to eliminate any trace of previous values
        recover(dataDir);
    }

    private static String applyHash(String sql) {
        return "left(hash('sha256', stringtoutf8(" + sql + "), 100000), 40)";
    }
}
