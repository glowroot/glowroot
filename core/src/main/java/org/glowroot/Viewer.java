/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot;

import java.io.File;
import java.net.URISyntaxException;
import java.security.CodeSource;

import javax.annotation.Nullable;

public class Viewer {

    private Viewer() {}

    public static void main(String... args) throws Exception {
        MainEntryPoint.runViewer(getGlowrootJarFile());
    }

    public static @Nullable File getGlowrootJarFile() throws URISyntaxException {
        CodeSource codeSource = Viewer.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            return null;
        }
        File glowrootJarFile = new File(codeSource.getLocation().toURI());
        if (glowrootJarFile.getName().equals("glowroot.jar")) {
            return glowrootJarFile;
        }
        return null;
    }
}
