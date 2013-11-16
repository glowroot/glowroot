/*
 * Copyright 2013 the original author or authors.
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import checkers.nullness.quals.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class Version {

    private static final Logger logger = LoggerFactory.getLogger(Version.class);

    private Version() {}

    static String getVersion() throws IOException {
        Manifest manifest = getManifest();
        if (manifest == null) {
            // manifest is missing when running ui testing and integration tests from inside IDE
            // so only log this at debug level
            logger.debug("could not locate META-INF/MANIFEST.MF file");
            return "unknown";
        }
        Attributes mainAttributes = manifest.getMainAttributes();
        String version = mainAttributes.getValue("Implementation-Version");
        if (version == null) {
            logger.warn("could not find Implementation-Version attribute in"
                    + " META-INF/MANIFEST.MF file");
            return "unknown";
        }
        if (version.endsWith("-SNAPSHOT")) {
            String commit = mainAttributes.getValue("Build-Commit");
            if (commit.length() > 0) {
                if (commit.length() == 40) {
                    version += ", commit " + commit.substring(0, 10);
                } else {
                    logger.warn("invalid Build-Commit attribute in META-INF/MANIFEST.MF file,"
                            + " should be a 40 character git commit hash");
                }
            }
            String snapshotTimestamp = mainAttributes.getValue("Build-Time");
            if (snapshotTimestamp == null) {
                logger.warn("could not find Build-Time attribute in META-INF/MANIFEST.MF file");
                return version;
            }
            version += ", built at " + snapshotTimestamp;
        }
        return version;
    }

    @Nullable
    private static Manifest getManifest() throws IOException {
        URL classURL = Version.class.getResource(Version.class.getSimpleName() + ".class");
        if (classURL == null) {
            logger.warn("url for Version class is unexpectedly null");
            return null;
        }
        String externalForm = classURL.toExternalForm();
        if (!externalForm.startsWith("jar:")) {
            return null;
        }
        URL manifestURL = new URL(externalForm.substring(0, externalForm.lastIndexOf("!")) +
                "!/META-INF/MANIFEST.MF");
        InputStream manifestIn = manifestURL.openStream();
        try {
            return new Manifest(manifestIn);
        } finally {
            manifestIn.close();
        }
    }
}
