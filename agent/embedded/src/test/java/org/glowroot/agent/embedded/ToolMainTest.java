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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

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
}
