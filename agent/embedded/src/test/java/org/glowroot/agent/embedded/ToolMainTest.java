/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.agent.embedded;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.security.CodeSource;
import java.security.cert.Certificate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ToolMainTest {

    @Test
    public void testNullCodeSource() throws URISyntaxException {
        assertThat(ToolMain.getGlowrootJarFile(null)).isNull();
    }

    @Test
    public void testWithGlowrootJar() throws Exception {
        File glowrootJar = new File("x/glowroot.jar").getAbsoluteFile();
        CodeSource codeSource = new CodeSource(glowrootJar.toURI().toURL(), new Certificate[0]);
        assertThat(ToolMain.getGlowrootJarFile(codeSource)).isEqualTo(glowrootJar);
    }

    @Test
    public void testWithNotGlowrootJar() throws Exception {
        File glowrootJar = new File("x/classes");
        CodeSource codeSource = new CodeSource(glowrootJar.toURI().toURL(), new Certificate[0]);
        assertThat(ToolMain.getGlowrootJarFile(codeSource)).isNull();
    }

    @Test
    public void describeUpgradeStateDetectsLegacyH2(@TempDir File dataDir) throws Exception {
        Files.write(new File(dataDir, "data.h2.db").toPath(), new byte[] {1});
        assertThat(ToolMain.describeUpgradeState(dataDir))
                .contains("data.h2.db")
                .contains("Layer 1")
                .contains("import-script");
    }

    @Test
    public void describeUpgradeStateDetectsMvStoreOnly(@TempDir File dataDir) throws Exception {
        Files.write(new File(dataDir, "data.mv.db").toPath(), new byte[] {1});
        assertThat(ToolMain.describeUpgradeState(dataDir))
                .contains("data.mv.db")
                .contains("No Layer 1");
    }

    @Test
    public void describeUpgradeStateNotesBothFiles(@TempDir File dataDir) throws Exception {
        Files.write(new File(dataDir, "data.h2.db").toPath(), new byte[] {1});
        Files.write(new File(dataDir, "data.mv.db").toPath(), new byte[] {2});
        assertThat(ToolMain.describeUpgradeState(dataDir))
                .contains("data.h2.db")
                .contains("data.mv.db also present");
    }

    @Test
    public void largeScriptWarnsAtOneGib() {
        assertThat(ToolMain.isLargeImportScript(1024L * 1024 * 1024)).isTrue();
        assertThat(ToolMain.isLargeImportScript(1024L * 1024 * 1024 - 1)).isFalse();
    }

    @Test
    public void restoreMvDbFromBakReplacesPartialFile(@TempDir File dataDir) throws Exception {
        File dbFile = new File(dataDir, "data.mv.db");
        File dbBakFile = new File(dataDir, "data.mv.db.bak");
        Files.write(dbBakFile.toPath(), new byte[] {9, 9});
        Files.write(dbFile.toPath(), new byte[] {1});
        assertThat(ToolMain.restoreMvDbFromBak(dbFile, dbBakFile)).isTrue();
        assertThat(dbFile).exists();
        assertThat(dbBakFile).doesNotExist();
        assertThat(Files.readAllBytes(dbFile.toPath())).containsExactly(9, 9);
    }

    @Test
    public void importScriptSupportsLegacyReservedKeywords(@TempDir File dataDir) throws Exception {
        File scriptFile = new File(dataDir, "export.sql");
        String sql = "CREATE MEMORY TABLE PUBLIC.TRACE(\n"
                + "    ID BIGINT,\n"
                + "    USER VARCHAR(255),\n"
                + "    VALUE VARCHAR(255)\n"
                + ");\n"
                + "INSERT INTO PUBLIC.TRACE (ID, USER, VALUE) VALUES (1, 'operator', 'custom');\n"
                + "CREATE CACHED TABLE PUBLIC.GAUGE_VALUE(\n"
                + "    GAUGE_ID BIGINT,\n"
                + "    CAPTURE_TIME BIGINT,\n"
                + "    VALUE DOUBLE\n"
                + ");\n"
                + "INSERT INTO PUBLIC.GAUGE_VALUE (GAUGE_ID, CAPTURE_TIME, VALUE) VALUES (42, 1000, 99.5);\n";
        Files.write(scriptFile.toPath(), sql.getBytes());

        ToolMain.importScript(dataDir, scriptFile);

        File dbFile = new File(dataDir, "data.mv.db");
        assertThat(dbFile).exists();

        String url = "jdbc:h2:" + dataDir.getPath() + File.separator + "data;NON_KEYWORDS=USER,VALUE;compress=true";
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT trace.user, trace.value FROM trace WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("user")).isEqualTo("operator");
                assertThat(rs.getString("value")).isEqualTo("custom");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT gauge_id, value FROM gauge_value WHERE gauge_id = 42")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("value")).isEqualTo(99.5);
            }
        }
    }

    @Test
    public void importScriptCleansUpPartialFileOnFailureWhenNoPriorDb(@TempDir File dataDir) throws Exception {
        File scriptFile = new File(dataDir, "invalid.sql");
        Files.write(scriptFile.toPath(), "INVALID SQL STATEMENT;".getBytes());

        File dbFile = new File(dataDir, "data.mv.db");
        assertThat(dbFile).doesNotExist();

        assertThatThrownBy(() -> ToolMain.importScript(dataDir, scriptFile))
                .isInstanceOf(Exception.class);

        assertThat(dbFile).doesNotExist();
    }

    @Test
    public void importScriptRestoresBakOnFailureWhenPriorDbExisted(@TempDir File dataDir) throws Exception {
        File dbFile = new File(dataDir, "data.mv.db");
        byte[] originalContent = new byte[] {42, 43, 44};
        Files.write(dbFile.toPath(), originalContent);

        File scriptFile = new File(dataDir, "invalid.sql");
        Files.write(scriptFile.toPath(), "INVALID SQL STATEMENT;".getBytes());

        assertThatThrownBy(() -> ToolMain.importScript(dataDir, scriptFile))
                .isInstanceOf(Exception.class);

        assertThat(dbFile).exists();
        assertThat(Files.readAllBytes(dbFile.toPath())).containsExactly(originalContent);
        assertThat(new File(dataDir, "data.mv.db.bak")).doesNotExist();
    }
}
