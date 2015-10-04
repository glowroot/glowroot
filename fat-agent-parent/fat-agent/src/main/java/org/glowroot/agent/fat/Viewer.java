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
package org.glowroot.agent.fat;

import java.io.File;
import java.net.URISyntaxException;
import java.security.CodeSource;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import org.glowroot.agent.MainEntryPoint;

public class Viewer {

    private Viewer() {}

    public static void main(String... args) throws Exception {
        CodeSource codeSource = Viewer.class.getProtectionDomain().getCodeSource();
        MainEntryPoint.runViewer(getGlowrootJarFile(codeSource));
    }

    @VisibleForTesting
    static @Nullable File getGlowrootJarFile(@Nullable CodeSource codeSource)
            throws URISyntaxException {
        if (codeSource == null) {
            return null;
        }
        File codeSourceFile = new File(codeSource.getLocation().toURI());
        if (codeSourceFile.getName().endsWith(".jar")) {
            return codeSourceFile;
        }
        return null;
    }
}
