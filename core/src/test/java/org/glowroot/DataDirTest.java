/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class DataDirTest {

    private static File glowrootJarFile;

    private static File customDataDir;

    @BeforeClass
    public static void setUp() throws IOException {
        glowrootJarFile = File.createTempFile("glowroot", ".jar");
        customDataDir = Files.createTempDir();
    }

    @AfterClass
    public static void tearDown() {
        glowrootJarFile.delete();
        customDataDir.delete();
    }

    @Test
    public void testWithNoDataDirProperty() {
        // given
        // when
        File dataDir = DataDir.getDataDir(ImmutableMap.<String, String>of(), null);
        // then
        assertThat(dataDir.getPath()).isEqualTo(".");
    }

    @Test
    public void testWithNoDataDirPropertyAndGlowrootJarFile() {
        // given
        // when
        File dataDir = DataDir.getDataDir(ImmutableMap.<String, String>of(), glowrootJarFile);
        // then
        assertThat(dataDir.getPath()).isEqualTo(glowrootJarFile.getParent());
    }

    @Test
    public void testWithAbsoluteDataDirProperty() {
        // given
        Map<String, String> properties = ImmutableMap.of("data.dir", customDataDir.getPath());
        // when
        File dataDir = DataDir.getDataDir(properties, null);
        // then
        assertThat(dataDir.getPath()).isEqualTo(customDataDir.getPath());
    }

    @Test
    public void testWithAbsoluteDataDirPropertyAndGlowrootJarFile() {
        // given
        Map<String, String> properties = ImmutableMap.of("data.dir", customDataDir.getPath());
        // when
        File dataDir = DataDir.getDataDir(properties, glowrootJarFile);
        // then
        assertThat(dataDir.getPath()).isEqualTo(customDataDir.getPath());
    }

    @Test
    public void testWithRelativeDataDirProperty() {
        // given
        Map<String, String> properties = ImmutableMap.of("data.dir", "x" + File.separator + "y");
        // when
        File dataDir = DataDir.getDataDir(properties, null);
        // then
        assertThat(dataDir.getPath()).isEqualTo("x" + File.separator + "y");
    }

    @Test
    public void testWithRelativeDataDirPropertyAndGlowrootJarFile() {
        // given
        Map<String, String> properties = ImmutableMap.of("data.dir", "x" + File.separator + "y");
        // when
        File dataDir = DataDir.getDataDir(properties, glowrootJarFile);
        // then
        assertThat(dataDir.getPath())
                .isEqualTo(new File(glowrootJarFile.getParentFile(), "x/y").getPath());
    }
}
