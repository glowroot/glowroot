/**
 * Copyright 2013 the original author or authors.
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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.Containers;
import io.informant.container.Container;
import io.informant.container.TempDirs;

import java.io.File;

import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ConfigFileLastModifiedTest {

    @Test
    public void shouldNotUpdateFileOnStartupIfNoChanges() throws Exception {
        // given
        File dataDir = TempDirs.createTempDir("informant-test-datadir");
        Container container = Containers.create(dataDir, 0, false);
        File configFile = new File(dataDir, "config.json");
        long originalLastModified = configFile.lastModified();
        // when
        container.close();
        container = Containers.create(dataDir, 0, true);
        long lastModified = configFile.lastModified();
        // then
        assertThat(lastModified).isEqualTo(originalLastModified);
        // cleanup
        container.close();
        TempDirs.deleteRecursively(dataDir);
    }
}
