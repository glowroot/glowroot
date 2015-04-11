/**
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
package org.glowroot.plugin.cassandra;

import org.glowroot.container.Container;
import org.glowroot.container.impl.JavaagentContainer;

// see https://github.com/jpountz/lz4-java/issues/59
public class TempWorkaround {

    static void applyWorkaround(Container container) throws Exception {
        if (container instanceof JavaagentContainer && !isShaded()) {
            container.addExpectedLogMessage("com.datastax.driver.core.FrameCompressor",
                    "Error loading LZ4 library"
                            + " (java.lang.AssertionError: java.lang.NullPointerException)."
                            + " LZ4 compression will not be available for the protocol.");
        }
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.shaded.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
