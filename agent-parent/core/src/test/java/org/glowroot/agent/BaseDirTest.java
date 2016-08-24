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
package org.glowroot.agent;

import java.io.File;
import java.io.IOException;

import com.google.common.io.Files;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class BaseDirTest {

    private static File glowrootJarFile;

    private static File customBaseDir;

    @BeforeClass
    public static void setUp() throws IOException {
        glowrootJarFile = File.createTempFile("glowroot", ".jar");
        customBaseDir = Files.createTempDir();
    }

    @AfterClass
    public static void tearDown() {
        glowrootJarFile.delete();
        customBaseDir.delete();
    }

    @Test
    public void testWithNoBaseDirPropertyAndGlowrootJarFile() {
        // given
        // when
        File baseDir = BaseDir.getBaseDir(null, glowrootJarFile);
        // then
        assertThat(baseDir.getPath()).isEqualTo(glowrootJarFile.getParent());
    }

    @Test
    public void testWithNoBaseDirPropertyAndGlowrootJarFileWithNullParent() {
        // given
        // when
        File baseDir = BaseDir.getBaseDir(null, new File("glowroot.jar"));
        // then
        assertThat(baseDir.getPath()).isEqualTo(new File(".").getPath());
    }

    @Test
    public void testWithAbsoluteBaseDirProperty() {
        // given
        // when
        File baseDir = BaseDir.getBaseDir(customBaseDir.getPath(), null);
        // then
        assertThat(baseDir.getPath()).isEqualTo(customBaseDir.getPath());
    }

    @Test
    public void testWithAbsoluteBaseDirPropertyAndGlowrootJarFile() {
        // given
        // when
        File baseDir = BaseDir.getBaseDir(customBaseDir.getPath(), glowrootJarFile);
        // then
        assertThat(baseDir.getPath()).isEqualTo(customBaseDir.getPath());
    }

    @Test
    public void testWithRelativeBaseDirPropertyAndGlowrootJarFile() {
        // given
        // when
        File baseDir = BaseDir.getBaseDir("x" + File.separator + "y", glowrootJarFile);
        // then
        assertThat(baseDir.getPath())
                .isEqualTo(new File(glowrootJarFile.getParentFile(), "x/y").getPath());
    }

    @Test
    public void testWithBadAbsoluteBaseDirPropertyAndGlowrootJarFile() {
        // given
        // when
        File baseDir = BaseDir.getBaseDir(glowrootJarFile.getPath(), glowrootJarFile);
        // then
        assertThat(baseDir.getPath()).isEqualTo(new File(".").getPath());
    }
}
