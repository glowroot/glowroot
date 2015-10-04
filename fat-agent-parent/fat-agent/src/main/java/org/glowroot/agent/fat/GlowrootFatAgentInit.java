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
package org.glowroot.agent.fat;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Map;

import javax.annotation.Nullable;

import org.glowroot.agent.GlowrootAgentInit;

public class GlowrootFatAgentInit implements GlowrootAgentInit {

    @Override
    public void init(File baseDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            String glowrootVersion, boolean jbossModules, boolean viewerModeEnabled)
                    throws Exception {

        GlowrootModule module = new GlowrootModule(baseDir, properties, instrumentation,
                glowrootJarFile, glowrootVersion, jbossModules, viewerModeEnabled);
        GlowrootModule.setInstance(module);
        if (instrumentation == null) {
            module.initEmbeddedServer();
        } else {
            module.initEmbeddedServerLazy(instrumentation);
        }
    }
}
