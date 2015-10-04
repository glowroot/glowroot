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
package org.glowroot.agent.weaving;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ClassLoadersTest {

    @Test
    public void shouldDeleteRegularFile() throws IOException {
        // given
        File file = File.createTempFile("glowroot-unit-test-", "");
        // when
        ClassLoaders.createDirectoryOrCleanPreviousContentsWithPrefix(file, "abc");
        // then
        assertThat(file.isDirectory()).isTrue();
    }

    @Test
    public void shouldCreateDirectory() throws IOException {
        // given
        File file = File.createTempFile("glowroot-unit-test-", "");
        file.delete();
        // when
        ClassLoaders.createDirectoryOrCleanPreviousContentsWithPrefix(file, "abc");
        // then
        assertThat(file.isDirectory()).isTrue();
    }
}
