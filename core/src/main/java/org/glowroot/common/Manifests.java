/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.jar.Manifest;

import javax.annotation.Nullable;

import com.google.common.io.Closer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Manifests {

    private static final Logger logger = LoggerFactory.getLogger(Manifests.class);

    private Manifests() {}

    public static @Nullable Manifest getManifest(Class<?> clazz) throws IOException {
        URL classURL = clazz.getResource(clazz.getSimpleName() + ".class");
        if (classURL == null) {
            logger.warn("url for class is unexpectedly null: {}", clazz);
            return null;
        }
        String externalForm = classURL.toExternalForm();
        if (!externalForm.startsWith("jar:")) {
            return null;
        }
        URL manifestURL = new URL(externalForm.substring(0, externalForm.lastIndexOf('!'))
                + "!/META-INF/MANIFEST.MF");
        // Closer is used to simulate Java 7 try-with-resources
        Closer closer = Closer.create();
        InputStream manifestIn = closer.register(manifestURL.openStream());
        try {
            return new Manifest(manifestIn);
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }
}
