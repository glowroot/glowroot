/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.init;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Map;

import javax.annotation.Nullable;

import org.glowroot.common.util.OnlyUsedByTests;

public interface GlowrootAgentInit {

    void init(@Nullable File pluginsDir, File confDir, @Nullable File sharedConfDir, File logDir,
            File tmpDir, @Nullable File glowrootJarFile, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, String glowrootVersion) throws Exception;

    @OnlyUsedByTests
    void setSlowThresholdToZero() throws Exception;

    @OnlyUsedByTests
    void resetConfig() throws Exception;

    @OnlyUsedByTests
    void close() throws Exception;

    @OnlyUsedByTests
    void awaitClose() throws Exception;
}
