/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.tests.javaagent;

import java.io.File;

import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.Container.StartupFailedException;
import org.glowroot.container.TempDirs;
import org.glowroot.container.javaagent.JavaagentContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DataDirLockTest {

    @Test
    public void shouldShutdown() throws Exception {
        // given
        File dataDir = TempDirs.createTempDir("glowroot-test-datadir");
        Container container = Containers.createWithFileDb(dataDir);
        // when
        boolean exception = false;
        try {
            // this test is only relevant using an external jvm for one of containers
            // since the data dir lock is held globally by the jvm
            JavaagentContainer.createWithFileDb(dataDir);
        } catch (StartupFailedException e) {
            exception = true;
        }
        // then
        assertThat(exception).isTrue();
        // cleanup
        container.close();
        TempDirs.deleteRecursively(dataDir);
    }
}
