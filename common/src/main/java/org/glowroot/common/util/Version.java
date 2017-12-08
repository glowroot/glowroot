/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Closer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Version {

    public static final String UNKNOWN_VERSION = "unknown";

    private static final Logger logger = LoggerFactory.getLogger(Version.class);

    private Version() {}

    public static String getVersion(Class<?> baseClass) {
        Manifest manifest;
        try {
            manifest = getManifest(baseClass);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return UNKNOWN_VERSION;
        }
        return getVersion(manifest);
    }

    @VisibleForTesting
    static @Nullable Manifest getManifest(Class<?> clazz) throws IOException {
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
        try {
            InputStream manifestIn = closer.register(manifestURL.openStream());
            return new Manifest(manifestIn);
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    @VisibleForTesting
    static String getVersion(@Nullable Manifest manifest) {
        if (manifest == null) {
            // manifest is missing when running ui testing and integration tests from inside IDE
            // so only log this at debug level
            logger.debug("could not locate META-INF/MANIFEST.MF file");
            return UNKNOWN_VERSION;
        }
        Attributes mainAttributes = manifest.getMainAttributes();
        String version = mainAttributes.getValue("Implementation-Version");
        if (version == null) {
            logger.warn("could not find Implementation-Version attribute in META-INF/MANIFEST.MF"
                    + " file");
            return UNKNOWN_VERSION;
        }
        if (version.endsWith("-SNAPSHOT")) {
            return getSnapshotVersion(version, mainAttributes);
        }
        String timestamp = mainAttributes.getValue("Build-Time");
        if (timestamp == null) {
            logger.warn("could not find Build-Time attribute in META-INF/MANIFEST.MF file");
            return version;
        }
        return version + ", built " + timestamp;
    }

    private static String getSnapshotVersion(String version, Attributes mainAttributes) {
        StringBuilder snapshotVersion = new StringBuilder(version);
        String commit = mainAttributes.getValue("Build-Commit");
        if (commit != null && !commit.equals("[none]")) {
            if (commit.length() == 40) {
                snapshotVersion.append(", commit ");
                snapshotVersion.append(commit.substring(0, 10));
            } else {
                logger.warn("invalid Build-Commit attribute in META-INF/MANIFEST.MF file,"
                        + " should be a 40 character git commit hash");
            }
        }
        String timestamp = mainAttributes.getValue("Build-Time");
        if (timestamp == null) {
            logger.warn("could not find Build-Time attribute in META-INF/MANIFEST.MF file");
            return snapshotVersion.toString();
        }
        snapshotVersion.append(", built ");
        snapshotVersion.append(timestamp);
        return snapshotVersion.toString();
    }
}
