/*
 * Copyright 2017 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// this is needed for JRebel 6.5.0+
// otherwise get JsonMappingException: "No serializer found for class
// org.glowroot.agent.shaded.org.glowroot.common.repo.MutableTimer"
// when viewing response time (average) page in the UI
public class JRebelWorkaround {

    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private JRebelWorkaround() {}

    public static void perform() {
        try {
            // cannot check ManagementFactory.getRuntimeMXBean().getInputArguments() here because
            // that can trigger java.util.logging.Logger to be loaded (e.g. on WebSphere 8.5) before
            // weaving is put in place (preventing the logger plugin from weaving JUL Logger)
            //
            // also cannot use ExecutorService here before weaving is put in place (preventing the
            // executor plugin from weaving ExecutorService)
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean shouldBeTrue;
                    try {
                        shouldBeTrue = JsonAutoDetect.Visibility.PUBLIC_ONLY
                                .isVisible(Object.class.getMethod("toString"));
                    } catch (Exception e) {
                        startupLogger.error(e.getMessage(), e);
                        return;
                    }
                    if (!shouldBeTrue) {
                        startupLogger.error("JRebel workaround did not work");
                    }
                }
            });
            thread.setName("Glowroot-Init-JRebel-Workaround");
            thread.start();
            thread.join();
        } catch (Exception e) {
            startupLogger.error(e.getMessage(), e);
        }
    }
}
