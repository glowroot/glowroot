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
package io.informant;

import java.io.IOException;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class Version {

    private static final Logger logger = LoggerFactory.getLogger(Version.class);

    private Version() {}

    static String getVersion() throws IOException {
        URL location = Version.class.getProtectionDomain().getCodeSource().getLocation();
        JarInputStream jarIn = new JarInputStream(location.openStream());
        try {
            Manifest manifest = jarIn.getManifest();
            if (manifest == null) {
                // manifest is missing when running ui testing and integration tests from inside IDE
                // so only log this at debug level
                logger.debug("could not find META-INF/MANIFEST.MF file");
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
                String snapshotTimestamp = mainAttributes.getValue("Build-Time");
                if (snapshotTimestamp == null) {
                    logger.warn("could not find Build-Time attribute in META-INF/MANIFEST.MF file");
                    return version;
                }
                return version + ", build " + snapshotTimestamp;
            }
            return version;
        } finally {
            jarIn.close();
        }
    }
}
