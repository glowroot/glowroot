/*
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
package io.informant.tests;

import java.io.File;

import org.junit.Test;

import io.informant.Containers;
import io.informant.container.Container;
import io.informant.container.Container.StartupFailedException;
import io.informant.container.TempDirs;
import io.informant.container.javaagent.JavaagentContainer;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DataSourceLockTest {

    @Test
    public void shouldShutdown() throws Exception {
        // given
        File dataDir = TempDirs.createTempDir("informant-test-datadir");
        // this test is only relevant using an external jvm for one of containers since H2
        // transparently handles two connections to the same file inside the same jvm with no
        // problem
        Container container = JavaagentContainer.createWithFileDb(dataDir);
        // when
        boolean exception = false;
        try {
            Containers.createWithFileDb(dataDir);
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
