/*
 * Copyright 2019-2023 the original author or authors.
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
package org.glowroot.agent.impl;

import java.io.File;

import com.google.common.io.Files;
import org.junit.jupiter.api.Test;

import org.glowroot.common.util.Clock;

import static com.google.common.base.Charsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class PreloadSuperTypesCacheTest {

    @Test
    public void shouldTruncateOnWritingToFile() throws Exception {
        // given
        File file = File.createTempFile("glowroot-unit-test-", "");

        PreloadSomeSuperTypesCache cache =
                new PreloadSomeSuperTypesCache(file, 100, Clock.systemClock());

        for (int i = 0; i < 101; i++) {
            cache.put("abc" + i, "xyz");
        }

        // when
        cache.writeToFile();

        // then
        assertThat(Files.readLines(file, UTF_8)).hasSize(80);

        // cleanup
        file.delete();
    }

    @Test
    public void shouldNotTruncateOnWritingToFile() throws Exception {
        // given
        File file = File.createTempFile("glowroot-unit-test-", "");

        PreloadSomeSuperTypesCache cache =
                new PreloadSomeSuperTypesCache(file, 100, Clock.systemClock());

        for (int i = 0; i < 100; i++) {
            cache.put("abc" + i, "xyz");
        }

        // when
        cache.writeToFile();

        // then
        assertThat(Files.readLines(file, UTF_8)).hasSize(100);

        // cleanup
        file.delete();
    }

    @Test
    public void shouldTruncateTruncateOnSecondWritingToFile() throws Exception {
        // given
        File file = File.createTempFile("glowroot-unit-test-", "");

        PreloadSomeSuperTypesCache cache =
                new PreloadSomeSuperTypesCache(file, 100, Clock.systemClock());

        for (int i = 0; i < 1000; i++) {
            cache.put("abc" + i, "xyz");
        }
        cache.writeToFile();

        // when
        for (int i = 1000; i < 1021; i++) {
            cache.put("abc" + i, "xyz");
        }
        cache.writeToFile();

        // then
        assertThat(Files.readLines(file, UTF_8)).hasSize(80);

        // cleanup
        file.delete();
    }

    @Test
    public void shouldNotTruncateOnSecondWritingToFile() throws Exception {
        // given
        File file = File.createTempFile("glowroot-unit-test-", "");

        PreloadSomeSuperTypesCache cache =
                new PreloadSomeSuperTypesCache(file, 100, Clock.systemClock());

        for (int i = 0; i < 1000; i++) {
            cache.put("abc" + i, "xyz");
        }
        cache.writeToFile();

        // when
        for (int i = 1000; i < 1020; i++) {
            cache.put("abc" + i, "xyz");
        }
        cache.writeToFile();

        // then
        assertThat(Files.readLines(file, UTF_8)).hasSize(100);

        // cleanup
        file.delete();
    }
}
