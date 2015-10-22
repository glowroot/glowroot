/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.tests;

import java.io.File;

import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TempDirs;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigFileLastModifiedTest {

    @Test
    public void shouldNotUpdateFileOnStartupIfNoChanges() throws Exception {
        // given
        File baseDir = TempDirs.createTempDir("glowroot-test-basedir");
        Container container = Containers.create(baseDir);
        File configFile = new File(baseDir, "config.json");
        long originalLastModified = configFile.lastModified();
        // when
        container.close();
        container = Containers.create(baseDir);
        long lastModified = configFile.lastModified();
        // then
        assertThat(lastModified).isEqualTo(originalLastModified);
        // cleanup
        container.close();
        TempDirs.deleteRecursively(baseDir);
    }
}
